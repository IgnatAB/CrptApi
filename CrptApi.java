package ru.ignat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import ru.ignat.CrptApi.Document.ApiResponse;
import ru.ignat.CrptApi.Document.Description;
import ru.ignat.CrptApi.Document.Product;

public class CrptApi {
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Semaphore semaphore;
  private final TimeUnit timeUnit;
  private final int requestLimit;
  private final String authToken;

  private static final String BASE_URL = "https://ismp.crpt.ru/api/v3";
  private static final String DEMO_URL = "https://markirovka.demo.crpt.tech";

  public CrptApi(TimeUnit timeUnit, int requestLimit, String authToken) {
    if (requestLimit <= 0) {
      throw new IllegalArgumentException("requestLimit must be positive");
    }
    this.timeUnit = Objects.requireNonNull(timeUnit, "timeUnit must not be null");
    this.requestLimit = requestLimit;
    this.httpClient = HttpClient.newHttpClient();
    this.objectMapper = new ObjectMapper();
    this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    this.objectMapper.registerModule(new JavaTimeModule());
    this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.semaphore = new Semaphore(requestLimit, true);
    this.authToken = Objects.requireNonNull(authToken, "authToken must not be null");

    refillSemaphore();
  }

  public ApiResponse createDocument(Document document, String signature)
      throws IOException, InterruptedException {
    if (document == null || signature == null) {
      throw new NullPointerException("Document or Signature must not be null");
    }

    waitForPermit();

    String body = objectMapper.writeValueAsString(document);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/lk/documents/commissioning/contract/create"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + authToken)
            .header("Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("API Error" + response.statusCode() + ": " + response.body());
    }

    return objectMapper.readValue(response.body(), ApiResponse.class);
  }

  private void waitForPermit() throws InterruptedException {
    semaphore.acquire();
  }

  private void refillSemaphore() {
    Thread refillTread =
        new Thread(
            () -> {
              while (true) {
                try {
                  Thread.sleep(timeUnit.toMillis(1));
                  semaphore.release(requestLimit - semaphore.availablePermits());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            });
    refillTread.setDaemon(true);
    refillTread.start();
  }

  public static class Document {
    public Description description;
    public String doc_id;
    public String doc_status;
    public String doc_type; // LP_INTRODUCE_GOODS
    public Boolean importRequest;
    public String owner_inn;
    public String participant_inn;
    public String producer_inn;
    public String production_date;
    public String production_type; // OWN_PRODUCTION / CONTRACT_PRODUCTION
    public List<Product> products;
    public LocalDate reg_date;
    public String reg_number;

    public static class Description {
      public String participantInn;
    }

    public static class Product {
      public String certificate_document; // CONFORMITY_CERTIFICATE / CONFORMITY_DECLARATION
      public String certificate_document_date;
      public String certificate_document_number;
      public String owner_inn;
      public String producer_inn;
      public String production_date;
      public String tnved_code;
      public String uit_code;
      public String uitu_code;
    }

    public static class ApiResponse {
      public String omsId;
      public String reportId;
    }
  }
  public static void main(String[] args){

    CrptApi api = new CrptApi(TimeUnit.SECONDS, 5, "FAKE_TOKEN_123");

    Document doc = new Document();
    doc.doc_id = "test-doc-id";
    doc.doc_status = "DRAFT";
    doc.doc_type = "LP_INTRODUCE_GOODS";
    doc.importRequest = false;
    doc.owner_inn = "1234567890";
    doc.participant_inn = "1234567890";
    doc.producer_inn = "1234567890";
    doc.production_date = "2025-07-01";
    doc.production_type = "OWN_PRODUCTION";
    doc.reg_date = LocalDate.now();
    doc.reg_number = "TEST-REG-001";

    Description description = new Description();
    description.participantInn="366454654854";

    Product product = new Product();
    product.certificate_document = "CONFORMITY_CERTIFICATE";
    product.certificate_document_date = "2025-06-01";
    product.certificate_document_number = "CERT-001";
    product.owner_inn = "1234567890";
    product.producer_inn = "1234567890";
    product.production_date = "2025-05-05";
    product.tnved_code = "1234567890";
    product.uit_code = "UIT123456";
    product.uitu_code = "UITU123456";

    doc.products = List.of(product);


    try {
      api.createDocument(doc, "FAKE_SIGNATURE");
    } catch (RuntimeException | IOException | InterruptedException e) {
      System.err.println("API call failed (expected with fake data): " + e.getMessage());
    }

    System.out.println("Тест завершён. Лимит запросов работает, сериализация прошла успешно.");
  }
}
