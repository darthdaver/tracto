package star.tracto;

import com.fasterxml.jackson.databind.ObjectMapper;
import star.tracto.data.exceptions.ServerErrorException;
import star.tracto.data.exceptions.ServerUnexpectedResponseCodeException;
import star.tracto.data.exceptions.TokenNotFoundException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Trasporto HTTP condiviso dai due generatori prefix. */
public final class PrefixServerClient {
    private PrefixServerClient() {}

    /** Esegue una GET e restituisce il testo della risposta. */
    public static String getText(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.connect();
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new TokenNotFoundException(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (status == HttpURLConnection.HTTP_INTERNAL_ERROR) {
                throw new ServerErrorException("Unexpected internal error: " + status);
            }
            throw new ServerUnexpectedResponseCodeException("Unexpected response code: " + status);
        } finally {
            connection.disconnect();
        }
    }

    /** Esegue una POST JSON e restituisce il campo body della risposta. */
    public static String postBody(String endpoint, ObjectMapper mapper, Object request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(300000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (var stream = connection.getOutputStream()) {
                mapper.writeValue(stream, request);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Prefix server returned HTTP " + status);
            }
            try (var stream = connection.getInputStream()) {
                return mapper.readTree(stream).required("body").asText();
            }
        } finally {
            connection.disconnect();
        }
    }
}
