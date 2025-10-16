/**
 * CrptApi.java - написанный на Java 11 потокобезопасный класс, для работы с API Честного знака.
 * Поддерживает ограничение на количество запросов к API.
 * Ограничение настраивается константами MAX_REQUESTS_DEFAULT максимального
 * количества запросов в определенный интервал времени DEFAULT_TIME_UNIT.
 *
 * README:
 * Перед запуском получите токен аутентификации на платформе Честный ЗНАК и вставьте его
 * в константу AUTH_TOKEN ниже.
 *
 * При необходимости измените базовый адрес:
 * Sandbox (по умолчанию): https://markirovka.sandbox.crptech.ru
 * Demo: https://markirovka.demo.crpt.tech
 * Production: https://ismp.crpt.ru
 *
 * Пример вызова метода:
 * CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);
 * CrptApi.Document doc = new CrptApi.Document();
 *
 * !заполнить поле открепленной подписью: api.createDocument(doc, "BASE64_SIGNATURE")
 *
 * Пример успешного ответа API:
 *  "value": "9abd3d41-76bc-4542-a88e-b1f7be8130b5"
 *
 * Используемые внешние библиотеки:
 * Jackson Databind и JsonInclude — сериализация объектов в JSON;
 * Jackson JavaTimeModule — поддержка LocalDate;
 *
 *
 * Важно:
 * Токен и подпись хранятся в коде только для тестового задания. В реальных проектах в коде их не
 * храним.
 */
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final String BASE_URL = "https://markirovka.sandbox.crptech.ru";
    private static final String DOCUMENT_PATH = "/api/v3/lk/documents/commissioning/contract/create";
    private static final String AUTH_TOKEN = "INSERT_YOUR_TOKEN";

    private static final int SEMAPHORE_TIMEOUT_MS = 10_000;
    private static final int MAX_REQUESTS_DEFAULT = 10;
    private static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.MINUTES;

    private final Semaphore semaphore;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int requestLimit;
    private final TimeUnit timeUnit;

    /**
     * Конструктор {@code CrptApi}.
     *
     * @param timeUnit временной интервал (секунда, минута и т.д.)
     * @param requestLimit количество запросов в интервал
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit != null ? timeUnit : DEFAULT_TIME_UNIT;
        this.requestLimit = requestLimit > 0 ? requestLimit : MAX_REQUESTS_DEFAULT;
        this.semaphore = new Semaphore(this.requestLimit, true);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        schedulePermitReplenish();
    }

    /**
     * Создает документ для ввода в оборот товара, который произведен в РФ).
     *
     * @param document объект документа (обязателен)
     * @param signature откреплённая подпись в Base64 (обязательна)
     * @throws IOException при сетевых ошибках
     * @throws InterruptedException при прерывании потока
     */
    public void createDocument(Document document, String signature)
        throws IOException, InterruptedException {
        if (document == null || signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Документ и подпись не могут быть пустыми.");
        }

        if (!semaphore.tryAcquire(SEMAPHORE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("Тайм-аут ожидания разрешения для запроса.");
        }

        ApiRequestBody body =
            new ApiRequestBody(
                DocumentFormat.MANUAL,
                objectMapper.writeValueAsString(document),
                ProductGroup.MILK,
                signature,
                DocumentType.LP_INTRODUCE_GOODS);

        String json = objectMapper.writeValueAsString(body);

        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + DOCUMENT_PATH + "?pg=milk"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.printf("Response code: %d%n", response.statusCode());
        System.out.printf("Response body: %s%n", response.body());

        semaphore.release();
    }

    /** Формат документа для API. */
    public enum DocumentFormat {
        MANUAL,
        XML,
        CSV
    }

    /** Товарная группа, в данном случае — молочная продукция. */
    public enum ProductGroup {
        MILK,
        SHOES,
        CLOTHES,
        PERFUMERY,
        PHARMA
    }

    /** Тип документа. Используется для определения операции (ввод в оборот, импорт, вывод и т.д.). */
    public enum DocumentType {
        LP_INTRODUCE_GOODS,
        LP_INTRODUCE_GOODS_CSV,
        LP_INTRODUCE_GOODS_XML
    }

    /** Тело запроса API. */
    private static class ApiRequestBody {
        public DocumentFormat documentFormat;
        public String productDocument;
        public ProductGroup productGroup;
        public String signature;
        public DocumentType type;

        public ApiRequestBody(DocumentFormat format, String doc, ProductGroup group,
            String signature, DocumentType type) {
            this.documentFormat = format;
            this.productDocument = doc;
            this.productGroup = group;
            this.signature = signature;
            this.type = type;
        }
    }

    /** Модель документа "Ввод в оборот. Производство РФ". */
    public static class Document {
        public Description description;
        public String docId;
        public String docStatus;
        public String docType;
        public boolean importRequest;
        public String ownerInn;
        public String participantInn;
        public String producerInn;
        public LocalDate productionDate;
        public String productionType;
        public List<Product> products;
        public LocalDate regDate;
        public String regNumber;
    }

    /** Описание документа (вспомогательная сущность). */
    public static class Description {
        public String participantInn;
    }

    /** Товар, входящий в документ. */
    public static class Product {
        public String certificateDocument;
        public LocalDate certificateDocumentDate;
        public String certificateDocumentNumber;
        public String ownerInn;
        public String producerInn;
        public LocalDate productionDate;
        public String tnvedCode;
        public String uitCode;
        public String uituCode;
    }

    /**
     * Фоновый поток пополняет семафор с заданной периодичностью, чтобы обеспечивать лимитирование
     * запросов.
     */
    private void schedulePermitReplenish() {
        Thread daemon =
            new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            timeUnit.sleep(1);
                            semaphore.release(requestLimit - semaphore.availablePermits());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        daemon.setDaemon(true);
        daemon.start();
    }

    /** Пример использования для локальной проверки. Перед запуском вставьте свой токен и подпись. */
    public static void main(String[] args) throws IOException, InterruptedException {
        CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);

        Document doc = new Document();
        doc.docId = "DOC-001";
        doc.docStatus = "NEW";
        doc.docType = "LP_INTRODUCE_GOODS";
        doc.importRequest = false;
        doc.ownerInn = "1234567890";
        doc.participantInn = "1234567890";
        doc.producerInn = "1234567890";
        doc.productionDate = LocalDate.now();
        doc.productionType = "SELF_PRODUCED";

        Description desc = new Description();
        desc.participantInn = "1234567890";
        doc.description = desc;

        Product product = new Product();
        product.certificateDocument = "1234567890";
        product.certificateDocumentDate = LocalDate.of(2025, Month.SEPTEMBER, 20);
        product.certificateDocumentNumber = "1234567890";
        product.ownerInn = "1234567890";
        product.producerInn = "1234567890";
        product.productionDate = LocalDate.now();
        product.tnvedCode = "0401201000";
        product.uitCode = "0000000000000000000000000001";
        product.uituCode = "0000000000000000000000000002";
        doc.products = List.of(product);

        doc.regDate = LocalDate.now();
        doc.regNumber = "RN-001";

        api.createDocument(doc, "BASE64_SIGNATURE");
    }
}
