package com.akshith.payledger.ledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;

// Region and credentials come from the SDK's default chain: AWS_REGION plus environment variables locally,
// or the IRSA web identity token on EKS.
@Configuration
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class AwsConfig {

    @Bean(destroyMethod = "close")
    SfnClient sfnClient() {
        return SfnClient.create();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqsClient() {
        return SqsClient.create();
    }
}
