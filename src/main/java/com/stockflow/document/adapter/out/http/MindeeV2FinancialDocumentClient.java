package com.stockflow.document.adapter.out.http;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stockflow.document.application.FinancialDocumentExtractionPort;
import com.stockflow.document.application.MindeeConfigurationException;
import com.stockflow.document.application.MindeeProviderException;
import com.stockflow.document.application.MindeeTimeoutException;
import com.stockflow.shared.config.MindeeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Mindee V2 protocol: Authorization contains the raw API key; enqueue is deliberately never retried. */
@Component
public class MindeeV2FinancialDocumentClient implements FinancialDocumentExtractionPort {
    private final MindeeProperties properties; private final HttpClient http; private final ObjectMapper json;
    @Autowired
    public MindeeV2FinancialDocumentClient(MindeeProperties properties, ObjectMapper json) {
        this(properties, null, json);
    }
    MindeeV2FinancialDocumentClient(MindeeProperties properties, HttpClient http, ObjectMapper json) { this.properties=properties;this.http=http;this.json=json; }

    @Override public ExtractionResult extract(ExtractionRequest request) {
        if (!properties.configured()) throw new MindeeConfigurationException();
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        try {
            JsonNode enqueued = send(HttpRequest.newBuilder(uri("/v2/inferences/enqueue")).timeout(remaining(deadline))
                    .header("Authorization", properties.apiKey()).header("Content-Type", "multipart/form-data; boundary=StockFlowBoundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(request))).build(), deadline);
            String jobId = text(enqueued, "job", "id"); if (jobId == null) jobId=text(enqueued,"id");
            if (jobId == null) throw new MindeeProviderException("invalid provider response");
            String inferenceId = poll(jobId, deadline);
            JsonNode inference=send(HttpRequest.newBuilder(uri("/v2/inferences/"+inferenceId)).timeout(remaining(deadline)).GET().build(),deadline);
            return map(inference);
        } catch (MindeeConfigurationException|MindeeProviderException|MindeeTimeoutException ex) { throw ex; }
          catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new MindeeTimeoutException(); }
          catch (Exception ex) { throw new MindeeProviderException("provider communication failed", ex); }
    }

    private String poll(String jobId,long deadline) throws Exception {
        while (true) {
            JsonNode jobResponse=send(HttpRequest.newBuilder(uri("/v2/jobs/"+jobId)).timeout(remaining(deadline)).GET().build(),deadline);
            JsonNode job=jobResponse.path("job"); String status=job.path("status").asText("");
            if ("SUCCESS".equals(status)) { String id=text(job,"inference_id"); if(id==null) id=text(job,"inference","id"); if(id==null) throw new MindeeProviderException("successful job lacks inference id"); return id; }
            if ("FAILED".equals(status)) throw new MindeeProviderException("provider job failed");
            if (!"WAITING".equals(status) && !"PROCESSING".equals(status)) throw new MindeeProviderException("unknown provider job state");
            long millis=Math.min(200, Math.max(1, Duration.ofNanos(Math.max(0,deadline-System.nanoTime())).toMillis()));
            if(System.nanoTime()>=deadline) throw new MindeeTimeoutException(); Thread.sleep(millis);
        }
    }
    private JsonNode send(HttpRequest request,long deadline) throws Exception {
        if(System.nanoTime()>=deadline) throw new MindeeTimeoutException();
        HttpResponse<String> response=client().send(request,HttpResponse.BodyHandlers.ofString()); int status=response.statusCode();
        if(status==401||status==402||status==429||status>=500) throw new MindeeProviderException("provider unavailable");
        if(status<200||status>=300) throw new MindeeProviderException("provider rejected request");
        return json.readTree(response.body());
    }
    private byte[] multipart(ExtractionRequest request) throws Exception {
        byte[] content; try(InputStream in=request.content()){content=in.readAllBytes();}
        String b="--StockFlowBoundary\r\n"; var out=new java.io.ByteArrayOutputStream();
        out.write((b+"Content-Disposition: form-data; name=\"model_id\"\r\n\r\n"+properties.modelId()+"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((b+"Content-Disposition: form-data; name=\"alias\"\r\n\r\nstockflow-"+request.documentId()+"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((b+"Content-Disposition: form-data; name=\"file\"; filename=\""+safeName(request.filename())+"\"\r\nContent-Type: "+request.mediaType()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(content); out.write("\r\n--StockFlowBoundary--\r\n".getBytes(StandardCharsets.UTF_8)); return out.toByteArray();
    }
    private ExtractionResult map(JsonNode response) {
        JsonNode result=response.path("inference").path("result"); if(result.isMissingNode()) result=response.path("result");
        JsonNode fields=result.path("fields"); if(fields.isMissingNode()) fields=result;
        return new ExtractionResult(value(fields,"supplier_name","supplier","vendor"),value(fields,"invoice_number","invoice_id"),date(value(fields,"invoice_date","date")), value(result.path("locale"),"currency"), amount(value(fields,"total_amount","total")));
    }
    private Optional<String> value(JsonNode node,String... names){ for(String n:names){JsonNode x=node.path(n); if(x.isObject())x=x.path("value"); if(x.isTextual()&&!x.asText().isBlank())return Optional.of(x.asText());} return Optional.empty(); }
    private Optional<LocalDate> date(Optional<String> value){try{return value.map(LocalDate::parse);}catch(Exception e){return Optional.empty();}}
    private Optional<BigDecimal> amount(Optional<String> value){try{return value.map(BigDecimal::new).filter(x->x.signum()>=0);}catch(Exception e){return Optional.empty();}}
    private static String text(JsonNode node,String... path){for(String p:path)node=node.path(p);return node.isTextual()?node.asText():null;}
    private URI uri(String path){return URI.create(properties.baseUrl()+path);} private Duration remaining(long deadline){long n=deadline-System.nanoTime();if(n<=0)throw new MindeeTimeoutException();return Duration.ofNanos(n);} private static String safeName(String n){return n==null?"document":n.replaceAll("[\\r\\n\\\"]", "_");}
    private HttpClient client() { return http == null ? HttpClient.newHttpClient() : http; }
}
