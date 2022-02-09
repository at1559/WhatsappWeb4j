package it.auties.whatsapp4j.request.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import it.auties.whatsapp4j.response.model.json.JsonResponseModel;
import it.auties.whatsapp4j.whatsapp.WhatsappAPI;
import it.auties.whatsapp4j.whatsapp.WhatsappConfiguration;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Session;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An abstract model class that represents a json request made from the client to the server
 *
 * @param <M> the type of the model
 */
@Slf4j
public abstract non-sealed class JsonRequest<M extends JsonResponseModel> extends Request<List<Object>, M> {
    /**
     * An instance of Jackson's writer
     */
    private static final ObjectWriter JACKSON = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /**
     * Constructs a new instance of a JsonRequest using a custom non null request tag
     *
     * @param tag the custom non null tag to assign to this request
     * @param configuration the configuration used for {@link WhatsappAPI}
     */
    protected JsonRequest(@NonNull String tag, @NonNull WhatsappConfiguration configuration) {
        super(tag, configuration);
    }

    /**
     * Constructs a new instance of a JsonRequest using the default request tag built using {@code configuration}
     *
     * @param configuration the configuration used for {@link WhatsappAPI}
     */
    protected JsonRequest(@NonNull WhatsappConfiguration configuration) {
        super(configuration);
    }

    /**
     * Sends a json request to the WebSocket linked to {@code session}
     *
     * @param session the WhatsappWeb's WebSocket session
     * @return this request
     **/
    @Override
    public @NonNull CompletableFuture<M> send(@NonNull Session session) {
        try {
            var body = buildBody();
            var json = JACKSON.writeValueAsString(body);
            var request = "%s,%s".formatted(tag, json);
            log.info("WhatsappRequest:{}", request);
            if (configuration.async()) {
                session.getAsyncRemote().sendObject(request, __ -> addRequest());
                return future();
            }

            session.getBasicRemote().sendObject(request);
            addRequest();
            return future();
        }catch (IOException exception){
            throw new RuntimeException("An exception occurred while sending a JSON message", exception);
        }catch (EncodeException exception){
            throw new RuntimeException("An exception occurred while encoding a JSON message", exception);
        }
    }
}
