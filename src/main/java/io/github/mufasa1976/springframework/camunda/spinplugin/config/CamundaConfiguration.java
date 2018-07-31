package io.github.mufasa1976.springframework.camunda.spinplugin.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.camunda.spin.DataFormats;
import org.camunda.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.camunda.spin.plugin.impl.SpinProcessEnginePlugin;
import org.camunda.spin.spi.DataFormatConfigurator;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.boot.autoconfigure.jackson.JacksonProperties;
import org.springframework.boot.bind.PropertySourcesPropertyValues;
import org.springframework.boot.bind.RelaxedDataBinder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

@Configuration
@Slf4j
public class CamundaConfiguration {
  public static class JacksonJsonDataFormatConfigurer implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, DataFormatConfigurator<JacksonJsonDataFormat> {
    private static PropertyValues propertyValues;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
      log.debug("Save the Property Values to a static Variable");
      ConfigurableEnvironment environment = event.getEnvironment();
      JacksonJsonDataFormatConfigurer.propertyValues = new PropertySourcesPropertyValues(environment.getPropertySources());
    }

    @Override
    public Class<JacksonJsonDataFormat> getDataFormatClass() {
      return JacksonJsonDataFormat.class;
    }

    @Override
    public void configure(JacksonJsonDataFormat dataFormat) {
      log.debug("Get the existing ObjectMapper of the Camunda Spin-Plugin");
      ObjectMapper objectMapper = dataFormat.getObjectMapper();

      JacksonProperties jacksonProperties = readJacksonProperties();
      Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder = new Jackson2ObjectMapperBuilder();

      // the following Lines and private Methods are copied from org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
      // because unfortunately the corresponding Configuration Class is marked as final and we can't use Spring-Beans here because we are within the standard Java SPI
      if (jacksonProperties.getDefaultPropertyInclusion() != null) {
        jackson2ObjectMapperBuilder.serializationInclusion(jacksonProperties.getDefaultPropertyInclusion());
      }
      if (jacksonProperties.getTimeZone() != null) {
        jackson2ObjectMapperBuilder.timeZone(jacksonProperties.getTimeZone());
      }
      configureFeatures(jackson2ObjectMapperBuilder, jacksonProperties.getDeserialization());
      configureFeatures(jackson2ObjectMapperBuilder, jacksonProperties.getSerialization());
      configureFeatures(jackson2ObjectMapperBuilder, jacksonProperties.getMapper());
      configureFeatures(jackson2ObjectMapperBuilder, jacksonProperties.getParser());
      configureFeatures(jackson2ObjectMapperBuilder, jacksonProperties.getGenerator());
      configureDateFormat(jackson2ObjectMapperBuilder, jacksonProperties);
      configurePropertyNamingStrategy(jackson2ObjectMapperBuilder, jacksonProperties);
      configureModules(jackson2ObjectMapperBuilder);
      configureLocale(jackson2ObjectMapperBuilder, jacksonProperties);

      jackson2ObjectMapperBuilder.configure(objectMapper);
    }

    private JacksonProperties readJacksonProperties() {
      JacksonProperties jacksonProperties = new JacksonProperties();
      RelaxedDataBinder jacksonPropertiesDataBinder = new RelaxedDataBinder(jacksonProperties, "spring.jackson");
      jacksonPropertiesDataBinder.bind(JacksonJsonDataFormatConfigurer.propertyValues);
      return jacksonProperties;
    }

    private void configureFeatures(Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder, Map<?, Boolean> features) {
      features.forEach((key, value) -> {
        if (BooleanUtils.isTrue(value)) {
          jackson2ObjectMapperBuilder.featuresToEnable(key);
        } else {
          jackson2ObjectMapperBuilder.featuresToDisable(key);
        }
      });
    }

    private void configureDateFormat(Jackson2ObjectMapperBuilder builder, JacksonProperties jacksonProperties) {
      // We support a fully qualified class name extending DateFormat or a date
      // pattern string value
      String dateFormat = jacksonProperties.getDateFormat();
      if (dateFormat != null) {
        try {
          Class<?> dateFormatClass = ClassUtils.forName(dateFormat, null);
          builder.dateFormat((DateFormat) BeanUtils.instantiateClass(dateFormatClass));
        } catch (ClassNotFoundException ex) {
          SimpleDateFormat simpleDateFormat = new SimpleDateFormat(dateFormat);
          // Since Jackson 2.6.3 we always need to set a TimeZone (see
          // gh-4170). If none in our properties fallback to the Jackson's
          // default
          TimeZone timeZone = jacksonProperties.getTimeZone();
          if (timeZone == null) {
            timeZone = new ObjectMapper().getSerializationConfig().getTimeZone();
          }
          simpleDateFormat.setTimeZone(timeZone);
          builder.dateFormat(simpleDateFormat);
        }
      }
    }

    private void configurePropertyNamingStrategy(Jackson2ObjectMapperBuilder builder, JacksonProperties jacksonProperties) {
      // We support a fully qualified class name extending Jackson's
      // PropertyNamingStrategy or a string value corresponding to the constant
      // names in PropertyNamingStrategy which hold default provided
      // implementations
      String strategy = jacksonProperties.getPropertyNamingStrategy();
      if (strategy != null) {
        try {
          configurePropertyNamingStrategyClass(builder, ClassUtils.forName(strategy, null));
        } catch (ClassNotFoundException ex) {
          configurePropertyNamingStrategyField(builder, strategy);
        }
      }
    }

    private void configurePropertyNamingStrategyClass(Jackson2ObjectMapperBuilder builder, Class<?> propertyNamingStrategyClass) {
      builder.propertyNamingStrategy((PropertyNamingStrategy) BeanUtils.instantiateClass(propertyNamingStrategyClass));
    }

    private void configurePropertyNamingStrategyField(Jackson2ObjectMapperBuilder builder, String fieldName) {
      // Find the field (this way we automatically support new constants
      // that may be added by Jackson in the future)
      Field field = ReflectionUtils.findField(PropertyNamingStrategy.class, fieldName, PropertyNamingStrategy.class);
      Assert.notNull(field, "Constant named '" + fieldName + "' not found on " + PropertyNamingStrategy.class.getName());
      try {
        builder.propertyNamingStrategy((PropertyNamingStrategy) field.get(null));
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    }

    private void configureModules(Jackson2ObjectMapperBuilder builder) {
      // redefine all used Modules also here because we can't scan any Application Context
      // simply because we are too early (Modules haven't been registered at this Time of
      // the Application Startup)
      builder.modulesToInstall(
          new ParameterNamesModule(JsonCreator.Mode.DEFAULT),
          new Jdk8Module(),
          new JavaTimeModule());
    }

    private void configureLocale(Jackson2ObjectMapperBuilder builder, JacksonProperties jacksonProperties) {
      Locale locale = jacksonProperties.getLocale();
      if (locale != null) {
        builder.locale(locale);
      }
    }
  }

  @Bean
  public ProcessEnginePlugin spinPlugin() {
    return new SpinProcessEnginePlugin() {
      @Override
      public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
        // use the ClassLoader of the Configuration Class. Normally this would be the Spring ClassLoader
        DataFormats.loadDataFormats(CamundaConfiguration.this.getClass().getClassLoader());
      }
    };
  }
}
