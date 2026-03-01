/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.bt2bs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.SalesforceMockHandler.jsonResponse;
import static com.salesforce.revoman.integration.core.bt2bs.ReVomanConfigForBT2BS.MILESTONE_CONFIG;
import static com.salesforce.revoman.integration.core.bt2bs.ReVomanConfigForBT2BS.MILESTONE_SETUP_CONFIG;
import static com.salesforce.revoman.integration.core.bt2bs.ReVomanConfigForBT2BS.PERSONA_CREATION_AND_SETUP_CONFIG;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.integration.core.SalesforceMockHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.collections.CollectionsKt;
import org.http4k.core.Response;
import org.http4k.core.Status;
import org.junit.jupiter.api.Test;

/**
 * Mock server companion test for {@link MilestoneBillingE2ETest}. Exercises the same Postman
 * collections and Kick configurations against a mock HTTP handler instead of a real Salesforce
 * server.
 */
class MilestoneBillingMockTest {

  private static final String PST_URI = "connect/rev/sales-transaction/actions/place";
  private static final String BT2BS_URI =
      "actions/standard/createBillingSchedulesFromBillingTransaction";
  private static final String INVOICE_URI =
      "commerce/invoicing/invoices/collection/actions/generate";

  @Test
  void testMilestoneBillingE2EWithMockServer() {
    var invoiceCallCount = new AtomicInteger(0);
    var mockHandler =
        SalesforceMockHandler.configure()
            // PST connect API
            .connectApiHandler(
                PST_URI,
                request ->
                    jsonResponse(
                        """
                        {"salesTransactionId":"mockSalesTxnId","requestIdentifier":"mockReqId","success":true}"""))
            // BT2BS invoke action
            .connectApiHandler(
                BT2BS_URI,
                request ->
                    jsonResponse(
                        """
                        [{"actionName":"createBillingSchedulesFromBillingTransaction","isSuccess":true,"outputValues":{"orderId":"mockBillingScheduleId"}}]"""))
            // Invoice generation
            .connectApiHandler(
                INVOICE_URI,
                request ->
                    Response.create(Status.OK)
                        .header("Content-Type", "application/json")
                        .body("{}"))
            // Order GET for activation
            .sobjectGetResponse(
                "Order",
                id ->
                    """
                    {"Id":"%s","Status":"Draft","attributes":{"type":"Order","url":"..."}}"""
                        .formatted(id))
            // SalesTransaction GET (polling) - already handled by default in SalesforceMockHandler
            // Composite queries:
            // 1st: query-order-and-related-records (from bmp-create-runtime)
            .compositeQueryResponse(
                handler ->
                    """
                    {"compositeResponse":[\
                    {"body":{"done":true,"records":[{"OrderNumber":"ORD-0001","CalculationStatus":"CompletedWithTax","attributes":{"type":"Order","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"order"},\
                    {"body":{"done":true,"records":[{"Id":"mockOrderItem1","OrderItemNumber":"OI-001","NetUnitPrice":63.00,"attributes":{"type":"OrderItem","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"orderItems"}\
                    ]}""")
            // 2nd: query-bs-bmp-bmpi
            .compositeQueryResponse(
                handler ->
                    """
                    {"compositeResponse":[\
                    {"body":{"done":true,"records":[{"Id":"mockBS1","Status":"Active","BillingMilestonePlanId":"mockBMP1","attributes":{"type":"BillingSchedule","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"billingSchedules"},\
                    {"body":{"done":true,"records":[{"Id":"mockBMP1","Status":"Active","attributes":{"type":"BillingMilestonePlan","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"bmps"},\
                    {"body":{"done":true,"records":[{"Id":"mockBMPI1","Status":"Pending","MilestoneAccomplishmentDate":"2026-04-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}},{"Id":"mockBMPI2","Status":"Pending","MilestoneAccomplishmentDate":"2026-05-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}},{"Id":"mockBMPI3","Status":"Pending","MilestoneAccomplishmentDate":"2026-06-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}}],"totalSize":3},"httpHeaders":{},"httpStatusCode":200,"referenceId":"bmpis"}\
                    ]}""")
            // 3rd: query-invoice-and-related-records (after first invoice)
            .compositeQueryResponse(
                handler ->
                    """
                    {"compositeResponse":[\
                    {"body":{"done":true,"records":[{"Id":"mockInvLine1","InvoiceId":"mockInv1","Invoice":{"Status":"Posted"},"attributes":{"type":"InvoiceLine","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"invoiceLines"},\
                    {"body":{"done":true,"records":[{"Id":"mockBS1","Status":"Active","attributes":{"type":"BillingSchedule","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"billingSchedules"},\
                    {"body":{"done":true,"records":[{"Id":"mockBMP1","Status":"Active","attributes":{"type":"BillingMilestonePlan","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"bmps"},\
                    {"body":{"done":true,"records":[{"Id":"mockBMPI1","Status":"Invoiced","MilestoneAccomplishmentDate":"2026-04-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}},{"Id":"mockBMPI2","Status":"Pending","MilestoneAccomplishmentDate":"2026-05-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}},{"Id":"mockBMPI3","Status":"Pending","MilestoneAccomplishmentDate":"2026-06-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}}],"totalSize":3},"httpHeaders":{},"httpStatusCode":200,"referenceId":"bmpis"}\
                    ]}""")
            // 4th: query-invoice-and-related-records (after all invoices)
            .compositeQueryResponse(
                handler ->
                    """
                    {"compositeResponse":[\
                    {"body":{"done":true,"records":[{"Id":"mockInvLine1","InvoiceId":"mockInv1","Invoice":{"Status":"Posted"},"attributes":{"type":"InvoiceLine","url":"..."}},{"Id":"mockInvLine2","InvoiceId":"mockInv2","Invoice":{"Status":"Posted"},"attributes":{"type":"InvoiceLine","url":"..."}}],"totalSize":2},"httpHeaders":{},"httpStatusCode":200,"referenceId":"invoiceLines"},\
                    {"body":{"done":true,"records":[{"Id":"mockBS1","Status":"CompletelyBilled","attributes":{"type":"BillingSchedule","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"billingSchedules"},\
                    {"body":{"done":true,"records":[{"Id":"mockBMP1","Status":"Completely Billed","attributes":{"type":"BillingMilestonePlan","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"bmps"},\
                    {"body":{"done":true,"records":[{"Id":"mockBMPI1","Status":"Invoiced","MilestoneAccomplishmentDate":"2026-04-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}},{"Id":"mockBMPI2","Status":"Invoiced","MilestoneAccomplishmentDate":"2026-05-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}},{"Id":"mockBMPI3","Status":"Invoiced","MilestoneAccomplishmentDate":"2026-06-01","attributes":{"type":"BillingMilestonePlanItem","url":"..."}}],"totalSize":3},"httpHeaders":{},"httpStatusCode":200,"referenceId":"bmpis"}\
                    ]}""")
            .build();

    var httpHandler = mockHandler.handler();
    final var mbRundown =
        ReVoman.revUp(
            (rundown, ignore) ->
                assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            PERSONA_CREATION_AND_SETUP_CONFIG.overrideHttpHandler(httpHandler),
            MILESTONE_SETUP_CONFIG.overrideHttpHandler(httpHandler),
            MILESTONE_CONFIG.overrideHttpHandler(httpHandler));
    assertThat(CollectionsKt.last(mbRundown).mutableEnv)
        .containsAtLeastEntriesIn(
            Map.of(
                "billingMilestonePlan1Status", "Completely Billed",
                "billingMilestonePlanItem1Status", "Invoiced",
                "billingSchedule1Status", "CompletelyBilled",
                "invoice1Status", "Posted",
                "invoice2Status", "Posted"));
  }
}
