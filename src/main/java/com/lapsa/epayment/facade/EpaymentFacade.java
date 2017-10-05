package com.lapsa.epayment.facade;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.lapsa.commons.function.MyCollections;
import com.lapsa.commons.function.MyMaps;
import com.lapsa.commons.function.MyNumbers;
import com.lapsa.commons.function.MyObjects;
import com.lapsa.commons.function.MyStrings;
import com.lapsa.fin.FinCurrency;
import com.lapsa.international.localization.LocalizationLanguage;
import com.lapsa.kkb.core.KKBOrder;
import com.lapsa.kkb.core.KKBPaymentRequestDocument;
import com.lapsa.kkb.core.KKBPaymentResponseDocument;
import com.lapsa.kkb.core.KKBPaymentStatus;
import com.lapsa.kkb.dao.KKBEntityNotFound;
import com.lapsa.kkb.dao.KKBOrderDAO;
import com.lapsa.kkb.mesenger.KKBNotificationChannel;
import com.lapsa.kkb.mesenger.KKBNotificationRecipientType;
import com.lapsa.kkb.mesenger.KKBNotificationRequestStage;
import com.lapsa.kkb.mesenger.KKBNotifier;
import com.lapsa.kkb.services.KKBDocumentComposerService;
import com.lapsa.kkb.services.KKBEpayConfigurationService;
import com.lapsa.kkb.services.KKBFactory;
import com.lapsa.kkb.services.KKBFormatException;
import com.lapsa.kkb.services.KKBResponseService;
import com.lapsa.kkb.services.KKBServiceError;
import com.lapsa.kkb.services.KKBValidationErrorException;
import com.lapsa.kkb.services.KKBWrongSignature;

@ApplicationScoped
public class EpaymentFacade {

    @Inject
    private KKBDocumentComposerService composer;

    @Inject
    private KKBResponseService responseService;

    @Inject
    private KKBEpayConfigurationService epayConfig;

    @Inject
    private KKBFactory factory;

    @Inject
    private KKBOrderDAO orderDAO;

    @Inject
    private KKBNotifier notifier;

    public PaymentBuilder newPaymentBuilder() {
	return new PaymentBuilder();
    }

    public ResponseBuilder newResponseBuilder() {
	return new ResponseBuilder();
    }

    public final class ResponseBuilder {

	private String responseXml;

	private ResponseBuilder() {
	}

	public ResponseBuilder withXml(String responseXml) {
	    this.responseXml = responseXml;
	    return this;
	}

	public Response build() {

	    KKBPaymentResponseDocument response = new KKBPaymentResponseDocument();
	    response.setCreated(Instant.now());
	    response.setContent(MyStrings.requireNonEmpty(responseXml, "Response is empty"));

	    // verify format
	    try {
		responseService.validateResponseXmlFormat(response);
	    } catch (KKBFormatException e) {
		throw new IllegalArgumentException("Wrong xml format", e);
	    }

	    // validate signature
	    try {
		responseService.validateSignature(response, true);
	    } catch (KKBServiceError e) {
		throw new RuntimeException("Internal error", e);
	    } catch (KKBWrongSignature e) {
		throw new IllegalArgumentException("Wrong signature", e);
	    }

	    // find order by id
	    KKBOrder order = null;
	    try {
		String orderId = responseService.parseOrderId(response, true);
		order = orderDAO.findByIdByPassCache(orderId);
	    } catch (KKBEntityNotFound e) {
		throw new IllegalArgumentException("No payment order found or reference is invlaid", e);
	    }

	    // validate response to request
	    KKBPaymentRequestDocument request = order.getLastRequest();
	    if (request == null)
		throw new RuntimeException("There is no request for response found"); // fatal

	    try {
		responseService.validateResponse(order, true);
	    } catch (KKBValidationErrorException e) {
		throw new IllegalArgumentException("Responce validation failed", e);
	    }

	    return new Response(response, order);
	}

	public final class Response {
	    private final KKBPaymentResponseDocument response;
	    private final KKBOrder order;

	    private KKBOrder handled;

	    private Response(final KKBPaymentResponseDocument response, final KKBOrder order) {
		this.order = MyObjects.requireNonNull(order);
		this.response = MyObjects.requireNonNull(response);
	    }

	    public Ebill handle() {
		if (handled != null)
		    throw new IllegalStateException("Already handled");

		// attach response
		order.setLastResponse(response);

		// set order status
		order.setStatus(KKBPaymentStatus.AUTHORIZATION_PASS);

		// paid instant
		Instant paymentInstant = responseService.parsePaymentTimestamp(response, true);
		order.setPaid(paymentInstant);

		// paid reference
		String paymentReference = responseService.parsePaymentReferences(response, true);
		order.setPaymentReference(paymentReference);

		handled = orderDAO.save(order);

		notifier.assignOrderNotification(KKBNotificationChannel.EMAIL, //
			KKBNotificationRecipientType.REQUESTER, //
			KKBNotificationRequestStage.PAYMENT_SUCCESS, //
			handled);

		return new EbillBuilder() //
			.withKKBOrder(order)
			.build();
	    }

	}
    }

    //

    public final class PaymentBuilder {
	private List<BuilderItem> items = new ArrayList<>();
	private String orderId;
	private String email;
	private LocalizationLanguage language;
	private String name;
	private String externalId;
	private FinCurrency currency;

	private PaymentBuilder() {
	}

	public PaymentBuilder withMoreItem(String productName, double cost, int quantity) {
	    items.add(new BuilderItem(productName, cost, quantity));
	    return this;
	}

	public PaymentBuilder winthGeneratedId() {
	    this.orderId = factory.generateNewOrderId();
	    return this;
	}

	public PaymentBuilder withOrderCurrencty(FinCurrency currency) {
	    this.currency = currency;
	    return this;
	}

	public PaymentBuilder withDefaultCurrency() {
	    this.currency = FinCurrency.KZT;
	    return this;
	}

	public PaymentBuilder withId(String orderId) {
	    this.orderId = orderId;
	    return this;
	}

	public PaymentBuilder withConsumer(String email, LocalizationLanguage language, String name) {
	    withConsumerLanguage(language);
	    withConsumerEmail(email);
	    withConsumerName(name);
	    return this;
	}

	public PaymentBuilder withConsumer(String email, LocalizationLanguage language) {
	    withConsumerLanguage(language);
	    withConsumerEmail(email);
	    return this;
	}

	public PaymentBuilder withConsumerName(String name) {
	    this.name = MyStrings.requireNonEmpty(name, "name");
	    return this;
	}

	public PaymentBuilder withConsumerEmail(String email) {
	    this.email = MyStrings.requireNonEmpty(email, "email");
	    return this;
	}

	public PaymentBuilder withExternalId(String externalId) {
	    this.externalId = MyStrings.requireNonEmpty(externalId);
	    return this;
	}

	public PaymentBuilder withExternalId(Integer externalId) {
	    this.externalId = MyNumbers.requireNonZero(externalId).toString();
	    return this;
	}

	public PaymentBuilder withConsumerLanguage(LocalizationLanguage language) {
	    this.language = MyObjects.requireNonNull(language, "language");
	    return this;
	}

	private final class BuilderItem {
	    private final String product;
	    private final double cost;
	    private final int quantity;

	    private BuilderItem(String product, double cost, int quantity) {
		this.product = MyStrings.requireNonEmpty(product, "product");
		this.cost = MyNumbers.requireNonZero(cost, "cost");
		this.quantity = MyNumbers.requireNonZero(quantity, "quantity");
	    }
	}

	public Payment build() {
	    KKBOrder o = new KKBOrder(MyStrings.requireNonEmpty(orderId, "orderId"));
	    o.setCreated(Instant.now());
	    o.setStatus(KKBPaymentStatus.NEW);
	    o.setCurrency(MyObjects.requireNonNull(currency, "currency"));
	    o.setConsumerEmail(MyStrings.requireNonEmpty(email, "email"));
	    o.setConsumerLanguage(MyObjects.requireNonNull(language, "language"));
	    o.setConsumerName(MyStrings.requireNonEmpty(name, "name"));
	    o.setExternalId(externalId);

	    MyCollections.requireNonEmpty(items, "items is empty") //
		    .stream() //
		    .forEach(x -> factory.generateNewOrderItem(x.product, x.cost, x.quantity, o));

	    return new Payment(o);
	}

	public final class Payment {

	    private final KKBOrder order;
	    private KKBOrder accepted;

	    private Payment(KKBOrder order) {
		this.order = MyObjects.requireNonNull(order);
	    }

	    public Ebill accept() {
		if (accepted != null)
		    throw new IllegalStateException("Already acceted");

		composer.composeCart(order);
		composer.composeRequest(order);

		accepted = orderDAO.save(order);

		notifier.assignOrderNotification(KKBNotificationChannel.EMAIL, //
			KKBNotificationRecipientType.REQUESTER, //
			KKBNotificationRequestStage.PAYMENT_LINK, //
			accepted);

		return new EbillBuilder() //
			.withKKBOrder(order)
			.build();
	    }
	}
    }

    public EbillBuilder newEbillBuilder() {
	return new EbillBuilder();
    }

    public final class EbillBuilder {
	private EbillBuilder() {
	}

	private String id;
	private String externalId;
	private EbillStatus status;
	private Instant created;
	private Double amount;
	private LocalizationLanguage consumerLanguage;
	private String consumerEmail;

	private Instant paid;
	private String reference;

	private List<EbillItem> items;

	private Ebill ebill;
	private String requestContent;
	private String consumerName;
	private String requestAppendix;
	private URI postbackURI;

	public EbillBuilder withFetched(String id) {
	    try {
		KKBOrder kkbOrder = orderDAO.findByIdByPassCache(MyStrings.requireNonEmpty(id, "id"));
		return withKKBOrder(kkbOrder);
	    } catch (KKBEntityNotFound e) {
		throw new IllegalArgumentException("not found", e);
	    }

	}

	public EbillBuilder withPostbackURI(URI postbackURI) {
	    this.postbackURI = postbackURI;
	    return this;
	}

	private EbillBuilder withKKBOrder(KKBOrder kkbOrder) {
	    this.id = kkbOrder.getId();
	    this.externalId = kkbOrder.getExternalId();
	    this.created = kkbOrder.getCreated();
	    this.amount = kkbOrder.getAmount();
	    this.consumerLanguage = kkbOrder.getConsumerLanguage();
	    this.consumerEmail = kkbOrder.getConsumerEmail();
	    this.consumerName = kkbOrder.getConsumerName();

	    this.items = kkbOrder.getItems().stream() //
		    .map(x -> new EbillItem(x.getName(), x.getCost(), x.getQuantity()))
		    .collect(Collectors.toList());

	    this.requestContent = kkbOrder.getLastRequest().getContentBase64();
	    this.requestAppendix = kkbOrder.getLastCart().getContentBase64();
	    this.paid = kkbOrder.getPaid();
	    this.reference = kkbOrder.getPaymentReference();

	    switch (kkbOrder.getStatus()) {
	    case NEW:
		this.status = EbillStatus.READY;
		break;
	    case AUTHORIZATION_FAILED:
		this.status = EbillStatus.FAILED;
		break;
	    case CANCELED:
		this.status = EbillStatus.CANCELED;
		break;
	    case COMPLETED:
	    case AUTHORIZATION_PASS:
	    case ENROLLED:
		this.status = EbillStatus.PAID;
		break;
	    default:
	    }
	    return this;
	}

	public Ebill build() {
	    if (ebill != null)
		throw new IllegalStateException("Already built");
	    switch (status) {
	    case READY:
		HttpFormTemplate form = new HttpFormTemplate(epayConfig.getEpayURL(), "POST",
			MyMaps.of( //
				"Signed_Order_B64", requestContent, //
				"template", epayConfig.getTemplateName(), //
				"email", consumerEmail, //
				"PostLink", postbackURI.toString(), // TODO move
								    // QAZKOM WS
								    // POSTBACK
								    // to own
				"Language", "%%LANGUAGE_TAG%%", //
				"appendix", requestAppendix, //
				"BackLink", "%%PAYMENT_PAGE_URL%%" //
			));
		ebill = new Ebill(id, externalId, status, created, amount, consumerLanguage, consumerEmail,
			consumerName, items,
			form);
		break;
	    case PAID:
		ebill = new Ebill(id, externalId, status, created, amount, consumerLanguage, consumerEmail,
			consumerName, items,
			paid,
			reference);
	    default:
	    }
	    return ebill;
	}

    }

    public final class Ebill {

	private final String id;
	private final String externalId;
	private final EbillStatus status;
	private final Instant created;
	private final Double amount;
	private final LocalizationLanguage consumerLanguage;
	private final String consumerEmail;
	private final String consumerName;

	private final List<EbillItem> items;

	private final HttpFormTemplate form;

	private final Instant paid;
	private final String reference;

	// constructor for unpayed ebill
	private Ebill(final String id, final String externalId, final EbillStatus status, final Instant created,
		final Double amount,
		final LocalizationLanguage consumerLanguage, final String consumerEmail, final String consumerName,
		List<EbillItem> items, HttpFormTemplate form) {

	    if (status != EbillStatus.READY)
		throw new IllegalArgumentException("Invalid status");

	    this.id = MyStrings.requireNonEmpty(id, "id");
	    this.externalId = externalId;
	    this.status = MyObjects.requireNonNull(status, "status");
	    this.created = MyObjects.requireNonNull(created);
	    this.amount = MyNumbers.requireNonZero(amount, "amount");
	    this.consumerLanguage = MyObjects.requireNonNull(consumerLanguage, "userLanguage");
	    this.consumerEmail = MyStrings.requireNonEmpty(consumerEmail);
	    this.consumerName = MyStrings.requireNonEmpty(consumerName);

	    this.items = Collections.unmodifiableList(MyCollections.requireNonNullElements(items));

	    this.form = MyObjects.requireNonNull(form);

	    this.paid = null;
	    this.reference = null;
	}

	// constructor for payed ebill
	private Ebill(final String id, final String externalId, final EbillStatus status, final Instant created,
		final Double amount,
		final LocalizationLanguage userLanguage, final String consumerEmail, final String consumerName,
		final List<EbillItem> items, final Instant paid,
		final String reference) {

	    if (status != EbillStatus.PAID)
		throw new IllegalArgumentException("Invalid status");

	    this.id = MyStrings.requireNonEmpty(id, "id");
	    this.externalId = externalId;
	    this.status = MyObjects.requireNonNull(status, "status");
	    this.created = MyObjects.requireNonNull(created);
	    this.amount = MyNumbers.requireNonZero(amount, "amount");
	    this.consumerLanguage = MyObjects.requireNonNull(userLanguage, "userLanguage");
	    this.consumerEmail = MyStrings.requireNonEmpty(consumerEmail);
	    this.consumerName = MyStrings.requireNonEmpty(consumerName);

	    this.items = Collections.unmodifiableList(MyCollections.requireNonNullElements(items));

	    this.form = null;

	    this.paid = MyObjects.requireNonNull(paid);
	    this.reference = MyStrings.requireNonEmpty(reference);
	}

	public String getId() {
	    return id;
	}

	public EbillStatus getStatus() {
	    return status;
	}

	public Instant getCreated() {
	    return created;
	}

	public Double getAmount() {
	    return amount;
	}

	public LocalizationLanguage getConsumerLanguage() {
	    return consumerLanguage;
	}

	public List<EbillItem> getItems() {
	    return items;
	}

	public HttpFormTemplate getForm() {
	    return form;
	}

	public Instant getPaid() {
	    return paid;
	}

	public String getReference() {
	    return reference;
	}

	public String getConsumerEmail() {
	    return consumerEmail;
	}

	public String getConsumerName() {
	    return consumerName;
	}

	public String getExternalId() {
	    return externalId;
	}

    }

    public static enum EbillStatus {
	READY, CANCELED, PAID, FAILED
    }

    public static class HttpFormTemplate {

	private final URL url;
	private final String method;
	private final Map<String, String> params;

	HttpFormTemplate(URL url, String method, Map<String, String> params) {
	    this.url = MyObjects.requireNonNull(url, "url");
	    this.method = MyStrings.requireNonEmpty(method);
	    this.params = Collections.unmodifiableMap(MyMaps.requireNonEmpty(MyObjects.requireNonNull(params)));
	}

	public URL getURL() {
	    return url;
	}

	public String getMethod() {
	    return method;
	}

	public Map<String, String> getParams() {
	    return params;
	}
    }

    public final class EbillItem {

	private final String name;
	private final Double amount;
	private final Integer quantity;

	private EbillItem(String name, Double amount, Integer quantity) {
	    this.name = MyStrings.requireNonEmpty(name);
	    this.amount = MyNumbers.requireNonZero(amount);
	    this.quantity = MyNumbers.requireNonZero(quantity);
	}

	public String getName() {
	    return name;
	}

	public Double getAmount() {
	    return amount;
	}

	public Integer getQuantity() {
	    return quantity;
	}
    }
}