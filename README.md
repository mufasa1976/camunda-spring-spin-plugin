# Content
This Project demonstrates the usage of the Camunda Spin Plugin and the Jackson ObjectMapper which will be configured with the same Properties as the Standard Spring Boot ObjectMapper

## Motivation
The Camunda Engine can be extended by Plugins. One of the most interesting is the Spin-Plugin with which you can serialize/deserialize the Process Variables
either with `aplication/json` or `application/xml` Mime-Type.

Within the Spring Framework Camunda Plugins can normally be registered as Spring Beans and they are then wired to the Camunda Engine.
But the Spin-Plugin uses the Jackson ObjectMapper to serialize/deserialize Process Variables as `application/json`.

This ObjectMapper is instantiated via the Standard Java SPI (Service Provider Interface) and therefor the're is no painless Way to
configure this ObjectMapper with non-standard Features.

## the long and winding Road
The Question is: how to configure this Spin-Plugin ObjectMapper with the same Configuration Properties as the standard Spring Boot ObjectMapper?

To be fair, it is a little tricky (and not less painful).

1. The Key is the Class `CamundaConfiguration.JacksonJsonDataFormatConfigurer`.  
   First, this Class is a Spring Boot `ApplicationListener` and second a `DataFormatConfigurator`
2. Because `CamundaConfiguration.JacksonJsonDataFormatConfigurer` listens for the `ApplicationEnvironmentPreparedEvent`  
   this Class must be registered as a Listener within the SpringBoot Main-Class `Application`.
   ```java
   new SpringApplicationBuilder(Application.class)
         .listeners(new CamundaConfiguration.JacksonJsonDataFormatConfigurer())
         .run(args);
   ```
3. When the Configuration Properties has been read this registered Listener will be called.  
   It will store the Configuration Properties within a __static__ Variable (because we are outside of any Container).
   ```java
   ConfigurableEnvironment environment = event.getEnvironment();
   JacksonJsonDataFormatConfigurer.propertyValues = new PropertySourcesPropertyValues(environment.getPropertySources());
   ```
4. Every `DataFormatConfigurator` must be registered as a Service to be called by the Java SPI.  
   So this Class must be registered within the File `META-INF/services/org.camunda.spin.spi.DataFormatConfigurator`:
   ```
   io.github.mufasa1976.springframework.camunda.spinplugin.config.CamundaConfiguration$JacksonJsonDataFormatConfigurer
   ```
   (static inner Classes must be separated by $)
5. To be sure that the `DataFormatConfigurator` uses the same ClassLoader as the Rest of the Application (the Spring Class-Loader)  
   the Spin-Plugin must be registered as a Bean __AND__ the ClassLoader of the Spin-Plugin must be replaced (instead it will
   use the standard SPI ClassLoader):
   ```java
   @Bean
   public ProcessEnginePlugin spinProcessEnginePlugin() {
     return new SpinProcessEnginePlugin() {
       @Override
       public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
         DataFormats.loadDataFormats(CamundaConfiguration.this.getClass().getClassLoader());
       }
     };
   }
   ```
   __ATTENTION__: you __MUST__ name the Spin-Plugin Bean `spinProcessEnginePlugin` or the Spin-Plugin will
   be wired twice (and the second time with the wrong ClassLoader).
6. At this Point the `JacksonJsonDataFormatConfigurer` will not be reloaded again. Instead the same ClassLoader  
   will be used and therefor the static Variable has the Content which has been read by the `ApplicationListener`.
7. By having the Configuration Properties _within_ a `DataFormatConfigurator` now you can reconfigure the ObjectMapper  
   of the Spin-Plugin with the Values of the Configuration Properties. Unfortunately Spring has decided to finalize the
   `StandardJackson2ObjectMapperBuilderCustomizer` we can't reuse it now. So we have to copy-and-paste the Code to
   configure the ObjectMapper.
8. Last but not least be aware that you have to register all Modules twice: one for Spring and also within  
   `JacksonJsonDataFormatConfigurator.configureModule()`. Normally we will register any Jackson-Module as a Spring-Bean
   and it will be registered to the ObjectMapper of Spring Boot.  
   But we are too early. Because SPI is another Instantiation Framework (as Spring is) we can't rely, that Spring has
   already loaded all additional Jackson-Modules.  
   Also we can't rely on __any__ `ApplicationContext` because at this Point we are outside of Spring.
   So this is really the only Thing to remember:
   > Always register any Jackson-Module twice. One for Spring, one for Spin !!!
9. Set the standard Camunda Serialization/Deserialization to `application/json`  
   ```yaml
   camunda:
     bpm:
       default-serialization-format: application/json
   ```

## Conclusion
Painful the Way a Developer has to go so that Camunda serializes with non-standard Configuration Properties as `application/json`.
But with this Receipt possible it is (Joda Language Style).