package com.akshith.payledger.gateway;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.web.bind.annotation.ControllerAdvice;

@ControllerAdvice
public class ApiExceptionHandler {

    @GraphQlExceptionHandler
    GraphQLError handle(ApiException e, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env).errorType(e.type()).message(e.getMessage()).build();
    }
}
