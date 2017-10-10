package com.lapsa.epayment.facade.beans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.ejb.Stateless;
import javax.inject.Inject;

import com.lapsa.commons.function.MyCollections;
import com.lapsa.commons.function.MyNumbers;
import com.lapsa.commons.function.MyObjects;
import com.lapsa.commons.function.MyStrings;
import com.lapsa.epayment.facade.Ebill;
import com.lapsa.epayment.facade.Ebill.EbillItem;
import com.lapsa.epayment.facade.Ebill.EbillStatus;
import com.lapsa.epayment.facade.EpaymentFacade;
import com.lapsa.fin.FinCurrency;
import com.lapsa.international.localization.LocalizationLanguage;
import com.lapsa.kkb.core.KKBOrder;
import com.lapsa.kkb.core.KKBPaymentStatus;
import com.lapsa.kkb.dao.KKBEntityNotFound;
import com.lapsa.kkb.dao.KKBOrderDAO;
import com.lapsa.kkb.mesenger.KKBNotificationChannel;
import com.lapsa.kkb.mesenger.KKBNotificationRecipientType;
import com.lapsa.kkb.mesenger.KKBNotificationRequestStage;
import com.lapsa.kkb.mesenger.KKBNotifier;
import com.lapsa.kkb.services.KKBDocumentComposerService;
import com.lapsa.kkb.services.KKBFactory;

@Stateless
public class EpaymentFacadeBean implements EpaymentFacade {

    @Inject
    private KKBDocumentComposerService composer;

    @Inject
    private KKBFactory factory;

    @Inject
    private KKBOrderDAO orderDAO;

    @Inject
    private KKBNotifier notifier;

    @Override
    public EbillAcceptorBuilder newEbillAcceptorBuilder() {
	return new EbillAcceptorBuilderImpl();
    }

    final class EbillAcceptorBuilderImpl implements EbillAcceptorBuilder {
	private List<BuilderItem> items = new ArrayList<>();
	private String orderId;
	private String email;
	private LocalizationLanguage language;
	private String name;
	private String externalId;
	private FinCurrency currency;

	private EbillAcceptorBuilderImpl() {
	}

	@Override
	public EbillAcceptorBuilder withMoreItem(String productName, double cost, int quantity) {
	    items.add(new BuilderItem(productName, cost, quantity));
	    return this;
	}

	@Override
	public EbillAcceptorBuilder winthGeneratedId() {
	    this.orderId = factory.generateNewOrderId();
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withOrderCurrencty(FinCurrency currency) {
	    this.currency = currency;
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withDefaultCurrency() {
	    this.currency = FinCurrency.KZT;
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withId(String orderId) {
	    this.orderId = orderId;
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withConsumer(String email, LocalizationLanguage language, String name) {
	    withConsumerLanguage(language);
	    withConsumerEmail(email);
	    withConsumerName(name);
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withConsumer(String email, LocalizationLanguage language) {
	    withConsumerLanguage(language);
	    withConsumerEmail(email);
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withConsumerName(String name) {
	    this.name = MyStrings.requireNonEmpty(name, "name");
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withConsumerEmail(String email) {
	    this.email = MyStrings.requireNonEmpty(email, "email");
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withExternalId(String externalId) {
	    this.externalId = MyStrings.requireNonEmpty(externalId, "externalId");
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withExternalId(Integer externalId) {
	    this.externalId = MyNumbers.requireNonZero(externalId, "externalId").toString();
	    return this;
	}

	@Override
	public EbillAcceptorBuilder withConsumerLanguage(LocalizationLanguage language) {
	    this.language = MyObjects.requireNonNull(language, "language");
	    return this;
	}

	final class BuilderItem {

	    private final String product;
	    private final double cost;
	    private final int quantity;

	    private BuilderItem(String product, double cost, int quantity) {
		this.product = MyStrings.requireNonEmpty(product, "product");
		this.cost = MyNumbers.requireNonZero(cost, "cost");
		this.quantity = MyNumbers.requireNonZero(quantity, "quantity");
	    }
	}

	@Override
	public EbillAcceptor build() {
	    KKBOrder o = new KKBOrder(MyStrings.requireNonEmpty(orderId, "orderId"));
	    o.setCreated(Instant.now());
	    o.setStatus(KKBPaymentStatus.NEW);
	    o.setCurrency(MyObjects.requireNonNull(currency, "currency"));
	    o.setConsumerEmail(MyStrings.requireNonEmpty(email, "email"));
	    o.setConsumerLanguage(MyObjects.requireNonNull(language, "language"));
	    o.setConsumerName(MyStrings.requireNonEmpty(name, "name"));
	    o.setExternalId(externalId);

	    MyCollections.requireNonEmpty(items, "items") //
		    .stream() //
		    .forEach(x -> factory.generateNewOrderItem(x.product, x.cost, x.quantity, o));

	    return new EbillAcceptorImpl(o);
	}

	final class EbillAcceptorImpl implements EbillAcceptor {

	    private boolean accepted = false;
	    final KKBOrder order;

	    private EbillAcceptorImpl(KKBOrder order) {
		this.order = MyObjects.requireNonNull(order, "order");
	    }

	    @Override
	    public Ebill accept() {
		if (accepted)
		    throw new IllegalStateException("Already accepted");

		composer.composeCart(order);
		composer.composeRequest(order);

		KKBOrder saved = orderDAO.save(order);

		notifier.assignOrderNotification(KKBNotificationChannel.EMAIL, //
			KKBNotificationRecipientType.REQUESTER, //
			KKBNotificationRequestStage.PAYMENT_LINK, //
			saved);

		accepted = true;

		return new EbillFetcherBuilderImpl() //
			.withKKBOrder(saved) //
			.build() //
			.fetch();
	    }
	}

    }

    @Override
    public EbillFetcherBuilder newEbillFetcherBuilder() {
	return new EbillFetcherBuilderImpl();
    }

    final class EbillFetcherBuilderImpl implements EbillFetcherBuilder {

	private KKBOrder order;

	private EbillFetcherBuilderImpl() {
	}

	@Override
	public EbillFetcherBuilder usingId(String id) {
	    try {
		return withKKBOrder(orderDAO.findByIdByPassCache(MyStrings.requireNonEmpty(id, "id")));
	    } catch (KKBEntityNotFound e) {
		throw new IllegalArgumentException("not found", e);
	    }
	}

	EbillFetcherBuilder withKKBOrder(KKBOrder order) {
	    this.order = MyObjects.requireNonNull(order, "order");
	    return this;
	}

	@Override
	public EbillFetcher build() {
	    return new EbillFetcherImpl(order);
	}

	final class EbillFetcherImpl implements EbillFetcher {

	    private boolean fetched = false;
	    final KKBOrder order;

	    private EbillFetcherImpl(KKBOrder order) {
		this.order = order;
	    }

	    @Override
	    public Ebill fetch() {
		if (fetched)
		    throw new IllegalStateException("Already accepted");

		String id = order.getId();
		String externalId = order.getExternalId();
		Instant created = order.getCreated();
		double amount = order.getAmount();
		LocalizationLanguage consumerLanguage = order.getConsumerLanguage();
		String consumerEmail = order.getConsumerEmail();
		String consumerName = order.getConsumerName();

		List<EbillItemImpl> items = order.getItems().stream() //
			.map(x -> new EbillItemImpl(x.getName(), x.getCost(), x.getQuantity()))
			.collect(Collectors.toList());

		Instant paid = order.getPaid();
		String reference = order.getPaymentReference();

		EbillStatus status;
		switch (order.getStatus()) {
		case NEW:
		    status = EbillStatus.READY;
		    break;
		case AUTHORIZATION_FAILED:
		    status = EbillStatus.FAILED;
		    break;
		case CANCELED:
		    status = EbillStatus.CANCELED;
		    break;
		case COMPLETED:
		case AUTHORIZATION_PASS:
		case ENROLLED:
		    status = EbillStatus.PAID;
		    break;
		default:
		    throw new IllegalStateException("Illegal status of the payment");
		}

		Ebill ebill = null;

		switch (status) {
		case READY:
		case CANCELED:
		case FAILED:
		    ebill = new EbillImpl(id, externalId, status, created, amount, consumerEmail, consumerName, items);
		    break;
		case PAID:
		    ebill = new EbillImpl(id, externalId, status, created, amount, consumerEmail, consumerName, items,
			    paid, reference);
		    break;
		default:
		    throw new IllegalStateException("Illegal status of the payment");
		}

		fetched = true;
		return ebill;
	    }

	}

    }

    final class EbillImpl implements Ebill {

	final String id;
	final String externalId;
	final EbillStatus status;
	final Instant created;
	final Double amount;
	final String consumerEmail;
	final String consumerName;

	final List<EbillItemImpl> items;

	final Instant paid;
	final String reference;

	// constructor for unpayed ebillImpl
	private EbillImpl(final String id, final String externalId, final EbillStatus status, final Instant created,
		final Double amount, final String consumerEmail, final String consumerName, List<EbillItemImpl> items) {

	    if (status != EbillStatus.READY)
		throw new IllegalArgumentException("Invalid status");

	    this.id = MyStrings.requireNonEmpty(id, "id");
	    this.externalId = externalId;
	    this.status = MyObjects.requireNonNull(status, "status");
	    this.created = MyObjects.requireNonNull(created, "created");
	    this.amount = MyNumbers.requireNonZero(amount, "amount");
	    this.consumerEmail = MyStrings.requireNonEmpty(consumerEmail, "consumerEmail");
	    this.consumerName = MyStrings.requireNonEmpty(consumerName, "consumerName");

	    this.items = Collections.unmodifiableList(MyCollections.requireNonNullElements(items, "items"));

	    this.paid = null;
	    this.reference = null;
	}

	// constructor for payed ebillImpl
	private EbillImpl(final String id, final String externalId, final EbillStatus status, final Instant created,
		final Double amount, final String consumerEmail, final String consumerName,
		final List<EbillItemImpl> items, final Instant paid, final String reference) {

	    if (status != EbillStatus.PAID)
		throw new IllegalArgumentException("Invalid status");

	    this.id = MyStrings.requireNonEmpty(id, "id");
	    this.externalId = externalId;
	    this.status = MyObjects.requireNonNull(status, "status");
	    this.created = MyObjects.requireNonNull(created, "created");
	    this.amount = MyNumbers.requireNonZero(amount, "amount");
	    this.consumerEmail = MyStrings.requireNonEmpty(consumerEmail, "consumerEmail");
	    this.consumerName = MyStrings.requireNonEmpty(consumerName, "consumerName");

	    this.items = Collections.unmodifiableList(MyCollections.requireNonNullElements(items, "items"));

	    this.paid = MyObjects.requireNonNull(paid, "paid");
	    this.reference = MyStrings.requireNonEmpty(reference, "reference");
	}

	@Override
	public String getId() {
	    return id;
	}

	@Override
	public EbillStatus getStatus() {
	    return status;
	}

	@Override
	public Instant getCreated() {
	    return created;
	}

	@Override
	public Double getAmount() {
	    return amount;
	}

	@Override
	public List<? extends EbillItem> getItems() {
	    return items;
	}

	@Override
	public Instant getPaid() {
	    return paid;
	}

	@Override
	public String getReference() {
	    return reference;
	}

	@Override
	public String getConsumerEmail() {
	    return consumerEmail;
	}

	@Override
	public String getConsumerName() {
	    return consumerName;
	}

	@Override
	public String getExternalId() {
	    return externalId;
	}

    }

    final class EbillItemImpl implements EbillItem {

	final String name;
	final Double amount;
	final Integer quantity;

	private EbillItemImpl(String name, Double amount, Integer quantity) {
	    this.name = MyStrings.requireNonEmpty(name, "name");
	    this.amount = MyNumbers.requireNonZero(amount, "amount");
	    this.quantity = MyNumbers.requireNonZero(quantity, "quantity");
	}

	@Override
	public String getName() {
	    return name;
	}

	@Override
	public Double getAmount() {
	    return amount;
	}

	@Override
	public Integer getQuantity() {
	    return quantity;
	}
    }
}