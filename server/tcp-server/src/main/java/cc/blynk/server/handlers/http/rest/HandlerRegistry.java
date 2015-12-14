package cc.blynk.server.handlers.http.rest;

import cc.blynk.server.handlers.http.Response;
import io.netty.handler.codec.http.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.uri.UriTemplate;

import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 09.12.15.
 */
public class HandlerRegistry {

    private static final Logger log = LogManager.getLogger(HandlerRegistry.class);

    private final static List<HandlerHolder> processors = new ArrayList<>();

    public static void register(Object o) {
        registerHandler(o);
    }

    private static void registerHandler(Object handler) {
        Class<?> handlerClass = handler.getClass();
        Annotation pathAnnotation = handlerClass.getAnnotation(Path.class);
        String handlerMainPath = ((Path) pathAnnotation).value();

        for (Method method : handlerClass.getMethods()) {
            Annotation consumes = method.getAnnotation(Consumes.class);
            String contentType = MediaType.APPLICATION_JSON;
            if (consumes != null) {
                contentType = ((Consumes) consumes).value()[0];
            }

            Annotation path = method.getAnnotation(Path.class);
            if (path != null) {
                String fullPath = handlerMainPath + ((Path) path).value();
                UriTemplate uriTemplate = new UriTemplate(fullPath);

                HandlerHolder handlerHolder = new HandlerHolder(uriTemplate, method, handler, method.getParameterCount());

                for (int i = 0; i < method.getParameterCount(); i++) {
                    Parameter parameter = method.getParameters()[i];

                    Annotation queryParamAnnotation = parameter.getAnnotation(QueryParam.class);
                    if (queryParamAnnotation != null) {
                        handlerHolder.params[i] = new QueyMethodParam(((QueryParam) queryParamAnnotation).value(), parameter.getType());
                    }

                    Annotation pathParamAnnotation = parameter.getAnnotation(PathParam.class);
                    if (pathParamAnnotation != null) {
                        handlerHolder.params[i] = new PathMethodParam(((PathParam) pathParamAnnotation).value(), parameter.getType());
                    }

                    if (pathParamAnnotation == null && queryParamAnnotation == null) {
                        handlerHolder.params[i] = new BodyMethodParam(parameter.getName(), parameter.getType(), contentType);
                    }
                }

                processors.add(handlerHolder);
            }
        }
    }

    public static FullHttpResponse process(HttpRequest req) {
        URIDecoder uriDecoder = new URIDecoder(req.getUri());
        Map<String, String> pathData = new HashMap<>();
        for (HandlerHolder handlerHolder : processors) {
            if (handlerHolder.httpMethod == req.getMethod() &&
                    handlerHolder.uriTemplate.match(uriDecoder.path(), pathData)) {
                if (req.getMethod() == HttpMethod.PUT || req.getMethod() == HttpMethod.POST) {
                    if (req instanceof HttpContent) {
                        uriDecoder.bodyData = ((HttpContent) req).content();
                        uriDecoder.contentType = req.headers().get(HttpHeaders.Names.CONTENT_TYPE);
                    }
                }
                uriDecoder.pathData = pathData;

                try {
                    Object[] params = handlerHolder.fetchParams(uriDecoder);
                    return (FullHttpResponse) handlerHolder.method.invoke(handlerHolder.handler, params);
                } catch (Exception e) {
                    log.error(e);
                    return Response.serverError();
                }
            }
        }

        log.error("Error resolving url. No path found.");
        return Response.notFound();
    }

}
