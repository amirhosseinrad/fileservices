package ir.ipaam.fileservice.domain.model.aggregate;

import ir.ipaam.fileservice.domain.command.CreatePdfCommand;
import ir.ipaam.fileservice.domain.event.PdfCreatedEvent;
import lombok.NoArgsConstructor;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.AggregateLifecycle;
import org.axonframework.spring.stereotype.Aggregate;

@Aggregate
@NoArgsConstructor
public class ConversionAggregate {

    @AggregateIdentifier
    private String conversionId;

    @CommandHandler
    public ConversionAggregate(CreatePdfCommand createPdfCommand) {

        AggregateLifecycle.apply(new PdfCreatedEvent(
            createPdfCommand.getConversionId(),
            createPdfCommand.getText(),
            null// payload generated later in projection/service
        ));
    }

    @EventSourcingHandler
    public void on(PdfCreatedEvent event) {
        this.conversionId = event.getConversionId();
    }
}
