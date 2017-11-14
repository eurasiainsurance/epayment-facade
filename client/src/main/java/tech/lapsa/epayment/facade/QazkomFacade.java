package tech.lapsa.epayment.facade;

import java.net.URI;

import javax.ejb.Local;

import tech.lapsa.epayment.domain.Invoice;
import tech.lapsa.java.commons.function.MyExceptions.IllegalArgument;
import tech.lapsa.java.commons.function.MyExceptions.IllegalState;

@Local
public interface QazkomFacade {
    Invoice handleResponse(String responseXml) throws IllegalArgument, IllegalState;

    PaymentMethod httpMethod(URI postbackURI, URI returnUri, Invoice forInvoice) throws IllegalArgument, IllegalState;
}