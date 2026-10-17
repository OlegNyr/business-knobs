package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.impl.ParameterPatcher;
import io.github.olegnyr.knobs.impl.model.ParameterPatch;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.util.MimeType;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Аварийная точка параметров {@code /actuator/parameters} — по
 * образцу {@code loggers}.
 *
 * <p>Чтение — действующие правки пода. Запись — {@code {author, reason, patch}}: правка ставится в
 * память этого пода, заменяет строку ключа целиком и снимается рестартом либо записью топика с
 * версией позже момента установки. Битая заплатка не применяется вовсе: ответ 400 в форме
 * ProblemDetail с ключами и причинами. Роль эксплуатации на эту точку назначает безопасность
 * сервиса; анонимный вызов закрыт. В поток аудита {@value #AUDIT_LOGGER} — заявленный автор,
 * субъект токена, причина и ключи, значений нет; свободный текст — в одну строку.
 *
 * <p>Саму правку и аудит делает {@link ParameterPatcher} без Spring; точка добавляет
 * субъект токена и форму ответа актуатора.
 */
@Endpoint(id = "parameters")
public class ParameterEndpoint {

    /** Поток аудита сервиса — отдельный логгер, чтобы сервис мог направить его в свой журнал аудита. */
    public static final String AUDIT_LOGGER = ParameterPatcher.AUDIT_LOGGER;

    private static final MimeType PROBLEM_JSON = MimeType.valueOf("application/problem+json");

    private final ParameterPatcher patcher;

    ParameterEndpoint(ParameterPatcher patcher) {
        this.patcher = patcher;
    }

    /**
     * Действующие аварийные правки пода.
     *
     * @return ключ, значение, автор, причина, момент установки
     */
    @ReadOperation
    public List<Map<String, Object>> patches() {
        return patcher.patches().stream().map(ParameterEndpoint::describe).toList();
    }

    /**
     * Ставит аварийную заплатку.
     *
     * @param author кто ставит
     * @param reason зачем
     * @param patch текст заплатки в формате параметра
     * @param securityContext субъект вызова — в аудит рядом с заявленным автором
     * @return 200 с поставленными ключами либо 400 с ключами и причинами
     */
    @WriteOperation
    public WebEndpointResponse<Map<String, Object>> patch(String author, String reason, String patch,
            SecurityContext securityContext) {
        ParameterPatcher.Result result = patcher.apply(author, subject(securityContext), reason, patch);
        if (!result.errors().isEmpty()) {
            return problem(result.errors());
        }
        return new WebEndpointResponse<>(Map.of("keys", result.keys()), WebEndpointResponse.STATUS_OK);
    }

    private static Map<String, Object> describe(ParameterPatch patch) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", patch.getKey());
        view.put("value", patch.getValue());
        view.put("author", patch.getAuthor());
        view.put("reason", patch.getReason());
        view.put("setAt", patch.getSetAt().toString());
        return view;
    }

    private static WebEndpointResponse<Map<String, Object>> problem(Map<String, String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Заплатка параметров не применена");
        body.put("status", WebEndpointResponse.STATUS_BAD_REQUEST);
        body.put("detail", "Заплатка не применена целиком: исправьте ключи из errors");
        body.put("errors", errors);
        return new WebEndpointResponse<>(body, WebEndpointResponse.STATUS_BAD_REQUEST, PROBLEM_JSON);
    }

    private static String subject(SecurityContext securityContext) {
        Principal principal = securityContext == null ? null : securityContext.getPrincipal();
        return principal == null ? "anonymous" : principal.getName();
    }
}
