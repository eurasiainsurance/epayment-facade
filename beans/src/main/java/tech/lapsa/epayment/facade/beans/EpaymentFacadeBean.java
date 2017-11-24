package tech.lapsa.epayment.facade.beans;

import static tech.lapsa.java.commons.function.MyExceptions.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

import javax.annotation.Resource;
import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.jms.Destination;

import tech.lapsa.epayment.dao.InvoiceDAO;
import tech.lapsa.epayment.dao.PaymentDAO;
import tech.lapsa.epayment.domain.Invoice;
import tech.lapsa.epayment.domain.Invoice.InvoiceBuilder;
import tech.lapsa.epayment.domain.Payment;
import tech.lapsa.epayment.facade.EpaymentFacade;
import tech.lapsa.epayment.facade.InvoiceNotFound;
import tech.lapsa.epayment.notifier.NotificationChannel;
import tech.lapsa.epayment.notifier.NotificationRecipientType;
import tech.lapsa.epayment.notifier.NotificationRequestStage;
import tech.lapsa.epayment.notifier.Notifier;
import tech.lapsa.java.commons.function.MyExceptions.IllegalArgument;
import tech.lapsa.java.commons.function.MyExceptions.IllegalState;
import tech.lapsa.java.commons.function.MyObjects;
import tech.lapsa.java.commons.function.MyStrings;
import tech.lapsa.java.commons.logging.MyLogger;
import tech.lapsa.javax.jms.JmsClientFactory;

@Stateless
public class EpaymentFacadeBean implements EpaymentFacade {

    @Inject
    private InvoiceDAO invoiceDAO;

    @Inject
    private PaymentDAO paymentDAO;

    @Inject
    private Notifier notifier;

    @Resource(lookup = Constants.JNDI_CONFIG)
    private Properties epaymentConfig;

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public URI getDefaultPaymentURI(final Invoice invoice) throws IllegalArgument, IllegalState {
	return reThrowAsChecked(() -> {
	    MyObjects.requireNonNull(invoice, "invoice");
	    final String pattern = epaymentConfig.getProperty(Constants.PROPERTY_DEFAULT_PAYMENT_URI_PATTERN);
	    try {
		final String parsed = pattern //
			.replace("@INVOICE_ID@", invoice.getNumber()) //
			.replace("@INVOICE_NUMBER@", invoice.getNumber()) //
			.replace("@LANG@", invoice.getConsumerPreferLanguage().getTag());
		return new URI(parsed);
	    } catch (final URISyntaxException e) {
		throw new IllegalArgumentException(e);
	    } catch (final NullPointerException e) {
		throw new IllegalArgumentException(e);
	    }
	});
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public Invoice accept(final Invoice invoice) throws IllegalArgument, IllegalState {
	return reThrowAsChecked(() -> {
	    final Invoice saved = invoiceDAO.save(invoice);

	    if (invoice.optionalConsumerEmail().isPresent()) {
		saved.unlazy();
		notifier.newNotificationBuilder() //
			.withChannel(NotificationChannel.EMAIL) //
			.withEvent(NotificationRequestStage.PAYMENT_LINK) //
			.withRecipient(NotificationRecipientType.REQUESTER) //
			.withProperty("paymentUrl", getDefaultPaymentURI(saved).toString()) //
			.forEntity(saved) //
			.build() //
			.onSuccess(x -> logger.FINE.log("Payment accepted notification sent '%1$s'", invoice)) //
			.send();
	    }
	    return saved;
	});
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public Invoice completeAndAccept(final InvoiceBuilder builder) throws IllegalArgument, IllegalState {
	return reThrowAsChecked(() -> {
	    return accept(builder.testingNumberWith(invoiceDAO::isUniqueNumber) //
		    .build());
	});
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public Invoice invoiceByNumber(final String number) throws IllegalArgument, IllegalState, InvoiceNotFound {
	return reThrowAsChecked(() -> {
	    MyStrings.requireNonEmpty(number, "number");
	    return invoiceDAO.optionalByNumber(MyStrings.requireNonEmpty(number, "number")) //
		    .orElseThrow(() -> new InvoiceNotFound());
	});
    }

    // TODO REFACT : rename JMS destination to epayment/jms/paidInvoices
    public static final String JNDI_JMS_DEST_PAID_INVOICES = "epayment/jms/paidEbills";

    @Resource(name = JNDI_JMS_DEST_PAID_INVOICES)
    private Destination paidInvoicesDestination;

    private MyLogger logger = MyLogger.newBuilder() //
	    .withNameOf(EpaymentFacade.class) //
	    .build();

    @Inject
    private JmsClientFactory factory;

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void invoiceHasPaid(final Invoice invoice, final Payment payment) throws IllegalArgument, IllegalState {
	reThrowAsChecked(() -> {

	    MyObjects.requireNonNull(invoice, "invoice");
	    MyObjects.requireNonNull(payment, "payment");

	    invoice.paidBy(payment);
	    invoiceDAO.save(invoice);
	    paymentDAO.save(payment);

	    logger.INFO.log("Ivoice has paid successfuly '%1$s'", invoice);

	    if (invoice.optionalConsumerEmail().isPresent()) {
		invoice.unlazy();
		notifier.newNotificationBuilder() //
			.withChannel(NotificationChannel.EMAIL) //
			.withEvent(NotificationRequestStage.PAYMENT_SUCCESS) //
			.withRecipient(NotificationRecipientType.REQUESTER) //
			.forEntity(invoice) //
			.build() //
			.onSuccess(x -> logger.FINE.log("Payment successful notification sent '%1$s'", invoice)) //
			.send();
	    }

	    invoice.unlazy();
	    try {
		factory.createConsumer(paidInvoicesDestination) //
			.accept(invoice);
		logger.FINE.log("Paid invoices notification queued '%1$s'", invoice);
	    } catch (final RuntimeException e) {
		throw new EJBException("Failed to send invoice payment info", e);
	    }

	});

    }

}
