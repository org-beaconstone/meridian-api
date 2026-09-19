package com.meridian;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
@Configuration
public class JsonConfiguration {
  @Bean Jackson2ObjectMapperBuilderCustomizer strictJson() { return builder -> builder
    .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS); }
}
