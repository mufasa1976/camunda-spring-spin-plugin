package io.github.mufasa1976.springframework.camunda.spinplugin;

import io.github.mufasa1976.springframework.camunda.spinplugin.config.CamundaConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class Application {
  public static void main(String... args) {
    new SpringApplicationBuilder(Application.class)
        .listeners(new CamundaConfiguration.JacksonJsonDataFormatConfigurer())
        .run(args);
  }
}
