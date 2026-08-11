package com.studyflow.common.config;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app (bundled into {@code classpath:/static/} by the Dockerfile) and
 * falls back to {@code index.html} for any unmatched GET so React Router's client-side routes
 * resolve on a hard refresh. Registered resource handlers rank below {@code @RequestMapping}
 * controllers in Spring's handler-mapping order, so this never shadows {@code /api/v1/**} — see
 * docs/DEPLOYMENT.md for why frontend and backend share one origin.
 */
@Configuration
@ConditionalOnResource(resources = "classpath:/static/index.html")
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/").resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        return requested.exists() && requested.isReadable() ? requested
                                : new ClassPathResource("/static/index.html");
                    }
                });
    }
}
