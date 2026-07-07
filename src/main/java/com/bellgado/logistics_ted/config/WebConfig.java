package com.bellgado.logistics_ted.config;

import com.bellgado.logistics_ted.web.audit.AuditLogInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuditLogInterceptor auditLogInterceptor;

    public WebConfig(AuditLogInterceptor auditLogInterceptor) {
        this.auditLogInterceptor = auditLogInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html", "/*.js", "/*.css", "/*.ico", "/*.png", "/*.svg")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditLogInterceptor).addPathPatterns("/api/**");
    }
}
