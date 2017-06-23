/**
 * Copyright 2016 Yurii Rashkovskii
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 */
package graphql.servlet;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.InvalidSyntaxError;
import graphql.execution.ExecutionStrategy;
import graphql.execution.instrumentation.Instrumentation;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Andrew Potter
 */
public abstract class GraphQLServlet extends HttpServlet implements Servlet, GraphQLMBean {

    public static final Logger log = LoggerFactory.getLogger(GraphQLServlet.class);

    public static final String APPLICATION_JSON_UTF8 = "application/json;charset=UTF-8";
    public static final int STATUS_OK = 200;
    public static final int STATUS_BAD_REQUEST = 400;

    private static final ObjectMapper mapper = new ObjectMapper();

    protected abstract GraphQLSchemaProvider getSchemaProvider();
    protected abstract GraphQLContext createContext(Optional<HttpServletRequest> request, Optional<HttpServletResponse> response);
    protected abstract ExecutionStrategyProvider getExecutionStrategyProvider();
    protected abstract Instrumentation getInstrumentation();
    protected abstract Map<String, Object> transformVariables(GraphQLSchema schema, String query, Map<String, Object> variables);

    private final List<GraphQLServletListener> listeners;
    private final ServletFileUpload fileUpload;

    private final RequestHandler getHandler;
    private final RequestHandler postHandler;

    public GraphQLServlet() {
        this(null, null);
    }

    public GraphQLServlet(List<GraphQLServletListener> listeners, FileItemFactory fileItemFactory) {
        this.listeners = listeners != null ? new ArrayList<>(listeners) : new ArrayList<>();
        this.fileUpload = new ServletFileUpload(fileItemFactory != null ? fileItemFactory : new DiskFileItemFactory());

        this.getHandler = (request, response) -> {
            final GraphQLContext context = createContext(Optional.of(request), Optional.of(response));
            String path = request.getPathInfo();
            if (path == null) {
                path = request.getServletPath();
            }
            if (path.contentEquals("/schema.json")) {
                query(IntrospectionQuery.INTROSPECTION_QUERY, null, new HashMap<>(), getSchemaProvider().getSchema(request), request, response, context);
            } else {
                if (request.getParameter("query") != null) {
                    final Map<String, Object> variables = new HashMap<>();
                    if (request.getParameter("variables") != null) {
                        variables.putAll(mapper.readValue(request.getParameter("variables"), new TypeReference<Map<String, Object>>() { }));
                    }
                    String operationName = null;
                    if (request.getParameter("operationName") != null) {
                        operationName = request.getParameter("operationName");
                    }
                    query(request.getParameter("query"), operationName, variables, getSchemaProvider().getReadOnlySchema(request), request, response, context);
                } else {
                    response.setStatus(STATUS_BAD_REQUEST);
                    log.info("Bad GET request: path was not \"/schema.json\" or no query variable named \"query\" given");
                }
            }
        };

        this.postHandler = (request, response) -> {
            final GraphQLContext context = createContext(Optional.of(request), Optional.of(response));
            GraphQLRequest graphQLRequest = null;

            try {
                InputStream inputStream = null;

                if (ServletFileUpload.isMultipartContent(request)) {
                    final Map<String, List<FileItem>> fileItems = fileUpload.parseParameterMap(request);

                    if (fileItems.containsKey("graphql")) {
                        final Optional<FileItem> graphqlItem = getFileItem(fileItems, "graphql");
                        if (graphqlItem.isPresent()) {
                            inputStream = graphqlItem.get().getInputStream();
                        }

                    } else if (fileItems.containsKey("query")) {
                        final Optional<FileItem> queryItem = getFileItem(fileItems, "query");
                        if (queryItem.isPresent()) {
                            graphQLRequest = new GraphQLRequest();
                            graphQLRequest.setQuery(new String(queryItem.get().get()));

                            final Optional<FileItem> operationNameItem = getFileItem(fileItems, "operationName");
                            if (operationNameItem.isPresent()) {
                                graphQLRequest.setOperationName(new String(operationNameItem.get().get()).trim());
                            }

                            final Optional<FileItem> variablesItem = getFileItem(fileItems, "variables");
                            if (variablesItem.isPresent()) {
                                String variables = new String(variablesItem.get().get());
                                if (!variables.isEmpty()) {
                                    graphQLRequest.setVariables((Map<String, Object>) mapper.readValue(variables, Map.class));
                                }
                            }
                        }
                    }

                    if (inputStream == null && graphQLRequest == null) {
                        response.setStatus(STATUS_BAD_REQUEST);
                        log.info("Bad POST multipart request: no part named \"graphql\" or \"query\"");
                        return;
                    }

                    context.setFiles(Optional.of(fileItems));

                } else {
                    // this is not a multipart request
                    inputStream = request.getInputStream();
                }

                if (graphQLRequest == null) {
                    graphQLRequest = mapper.readValue(inputStream, GraphQLRequest.class);
                }

            } catch (Exception e) {
                log.info("Bad POST request: parsing failed", e);
                response.setStatus(STATUS_BAD_REQUEST);
                return;
            }

            Map<String,Object> variables = graphQLRequest.getVariables();
            if (variables == null) {
                variables = new HashMap<>();
            }

            query(graphQLRequest.getQuery(), graphQLRequest.getOperationName(), variables, getSchemaProvider().getSchema(request), request, response, context);
        };
    }

    public void addListener(GraphQLServletListener servletListener) {
        listeners.add(servletListener);
    }

    public void removeListener(GraphQLServletListener servletListener) {
        listeners.remove(servletListener);
    }

    @Override
    public String[] getQueries() {
        return getSchemaProvider().getSchema().getQueryType().getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).toArray(String[]::new);
    }

    @Override
    public String[] getMutations() {
        return getSchemaProvider().getSchema().getMutationType().getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).toArray(String[]::new);
    }

    @Override
    public String executeQuery(String query) {
        try {
            final ExecutionResult result = newGraphQL(getSchemaProvider().getSchema()).execute(query, createContext(Optional.empty(), Optional.empty()), new HashMap<>());
            return mapper.writeValueAsString(createResultFromDataAndErrors(result.getData(), result.getErrors()));
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private void doRequest(HttpServletRequest request, HttpServletResponse response, RequestHandler handler) {

        List<GraphQLServletListener.RequestCallback> requestCallbacks = runListeners(l -> l.onRequest(request, response));

        try {
            handler.handle(request, response);
            runCallbacks(requestCallbacks, c -> c.onSuccess(request, response));
        } catch (Throwable t) {
            response.setStatus(500);
            log.error("Error executing GraphQL request!", t);
            runCallbacks(requestCallbacks, c -> c.onError(request, response, t));
        } finally {
            runCallbacks(requestCallbacks, c -> c.onFinally(request, response));
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doRequest(req, resp, getHandler);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doRequest(req, resp, postHandler);
    }

    private Optional<FileItem> getFileItem(Map<String, List<FileItem>> fileItems, String name) {
        List<FileItem> items = fileItems.get(name);
        if(items == null || items.isEmpty()) {
            return Optional.empty();
        }

        return items.stream().findFirst();
    }

    private GraphQL newGraphQL(GraphQLSchema schema) {
        ExecutionStrategyProvider executionStrategyProvider = getExecutionStrategyProvider();
        return GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(executionStrategyProvider.getQueryExecutionStrategy())
            .mutationExecutionStrategy(executionStrategyProvider.getMutationExecutionStrategy())
            .subscriptionExecutionStrategy(executionStrategyProvider.getSubscriptionExecutionStrategy())
            .instrumentation(getInstrumentation())
            .build();
    }

    private void query(String query, String operationName, Map<String, Object> variables, GraphQLSchema schema, HttpServletRequest req, HttpServletResponse resp, GraphQLContext context) throws IOException {
        if (operationName != null && operationName.isEmpty()) {
            query(query, null, variables, schema, req, resp, context);
        } else
            if (Subject.getSubject(AccessController.getContext()) == null && context.getSubject().isPresent()) {
            Subject.doAs(context.getSubject().get(), (PrivilegedAction<Void>) () -> {
                try {
                    query(query, operationName, variables, schema, req, resp, context);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        } else {
            List<GraphQLServletListener.OperationCallback> operationCallbacks = runListeners(l -> l.onOperation(context, operationName, query, variables));

            final ExecutionResult executionResult = newGraphQL(schema).execute(query, operationName, context, transformVariables(schema, query, variables));
            final List<GraphQLError> errors = executionResult.getErrors();
            final Object data = executionResult.getData();

            final String response = mapper.writeValueAsString(createResultFromDataAndErrors(data, errors));

            resp.setContentType(APPLICATION_JSON_UTF8);
            resp.setStatus(STATUS_OK);
            resp.getWriter().write(response);

            if(errorsPresent(errors)) {
                runCallbacks(operationCallbacks, c -> c.onError(context, operationName, query, variables, data, errors));
            } else {
                runCallbacks(operationCallbacks, c -> c.onSuccess(context, operationName, query, variables, data));
            }

            runCallbacks(operationCallbacks, c -> c.onFinally(context, operationName, query, variables, data));
        }
    }

    private Map<String, Object> createResultFromDataAndErrors(Object data, List<GraphQLError> errors) {

        final Map<String, Object> result = new HashMap<>();
        result.put("data", data);

        if (errorsPresent(errors)) {
            final List<GraphQLError> clientErrors = filterGraphQLErrors(errors);
            if (clientErrors.size() < errors.size()) {
                // Some errors were filtered out to hide implementation - put a generic error in place.
                clientErrors.add(new GenericGraphQLError("Internal Server Error(s) while executing query"));

                errors.stream()
                    .filter(error -> !isClientError(error))
                    .forEach(error -> {
                        log.error("Error executing query ({}): {}", error.getClass().getSimpleName(), error.getMessage());
                    });
            }
            result.put("errors", clientErrors);
        }

        return result;
    }

    private boolean errorsPresent(List<GraphQLError> errors) {
        return errors != null && !errors.isEmpty();
    }

    protected List<GraphQLError> filterGraphQLErrors(List<GraphQLError> errors) {
        return errors.stream()
            .filter(this::isClientError)
            .collect(Collectors.toList());
    }

    protected boolean isClientError(GraphQLError error) {
        return error instanceof InvalidSyntaxError || error instanceof ValidationError;
    }

    private <R> List<R> runListeners(Function<? super GraphQLServletListener, R> action) {
        if (listeners == null) {
            return Collections.emptyList();
        }

        return listeners.stream()
            .map(listener -> {
                try {
                    return action.apply(listener);
                } catch (Throwable t) {
                    log.error("Error running listener: {}", listener, t);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private <T> void runCallbacks(List<T> callbacks, Consumer<T> action) {
        callbacks.forEach(callback -> {
            try {
                action.accept(callback);
            } catch (Throwable t) {
                log.error("Error running callback: {}", callback, t);
            }
        });
    }

    /**
     * Don't let listener errors escape to the client.
     */
    private <T, R> void runListener(T listener, Function<? super T, ? super R> action) {
    }

    protected static class VariablesDeserializer extends JsonDeserializer<Map<String, Object>> {
        @Override
        public Map<String, Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            final Object o = p.readValueAs(Object.class);
            if (o instanceof Map) {
                return (Map<String, Object>) o;
            } else if (o instanceof String) {
                return mapper.readValue((String) o, new TypeReference<Map<String, Object>>() {});
            } else {
                throw new RuntimeJsonMappingException("variables should be either an object or a string");
            }
        }
    }

    protected static class GraphQLRequest {
        private String query;
        @JsonDeserialize(using = GraphQLServlet.VariablesDeserializer.class)
        private Map<String, Object> variables = new HashMap<>();
        private String operationName;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }

        public String getOperationName() {
            return operationName;
        }

        public void setOperationName(String operationName) {
            this.operationName = operationName;
        }
    }

    protected interface RequestHandler extends BiConsumer<HttpServletRequest, HttpServletResponse> {
        @Override
        default void accept(HttpServletRequest request, HttpServletResponse response) {
            try {
                handle(request, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void handle(HttpServletRequest request, HttpServletResponse response) throws Exception;
    }
}
