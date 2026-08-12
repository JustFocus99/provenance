package io.collective.articles;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.collective.restsupport.BasicHandler;
import org.eclipse.jetty.server.Request;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.stream.Collectors;

public class ArticlesController extends BasicHandler {
    private final ArticleDataGateway gateway;

    public ArticlesController(ObjectMapper mapper, ArticleDataGateway gateway) {
        super(mapper);
        this.gateway = gateway;
    }

    @Override
    public void handle(String target, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        get("/articles", List.of("application/json", "text/html"), request, servletResponse, () -> {
            List<ArticleInfo> infos = gateway.findAll().stream()
                    .map(record -> new ArticleInfo(record.getId(), record.getTitle()))
                    .collect(Collectors.toList());

            writeJsonBody(servletResponse, infos);
        });

        get("/available", List.of("application/json"), request, servletResponse, () -> {
            List<ArticleInfo> infos = gateway.findAvailable().stream()
                    .map(record -> new ArticleInfo(record.getId(), record.getTitle()))
                    .collect(Collectors.toList());

            writeJsonBody(servletResponse, infos);
        });
    }
}
