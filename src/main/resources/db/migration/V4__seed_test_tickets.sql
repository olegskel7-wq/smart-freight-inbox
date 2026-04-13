-- 50 test email tickets for demo purposes
-- Covers all statuses: NEW, PROCESSING, DRAFT_READY, FAILED, CLOSED
-- Uses container numbers from V2 seed data

INSERT INTO email_ticket
    (message_id, sender_email, subject, body, extracted_container_number, status, draft_reply, retry_count, deleted, created_at, updated_at, version)
VALUES

-- DRAFT_READY tickets (AI has generated replies) --

('<MSG001@freightco.com>', 'john.harris@freightco.com', 'Urgent: Container status enquiry MSCU1234567',
'Dear Smart Freight Team,

Could you please provide an update on the status of container MSCU1234567?
We have a customer awaiting delivery and need to confirm the ETA.

Best regards,
John Harris
FreightCo Ltd',
'MSCU1234567', 'DRAFT_READY',
'Dear Mr Harris,

Thank you for your enquiry regarding container MSCU1234567.

We can confirm that your container is currently IN TRANSIT aboard Vessel MSC Francesca, Bay 14. The estimated time of arrival is 20 April 2026 at 08:00.

We will keep you updated on any changes to the schedule. Please do not hesitate to contact us if you require any further information.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour', 1),

('<MSG002@logistics-eu.com>', 'anna.schmidt@logistics-eu.com', 'Container HLXU8764321 - Port arrival confirmation',
'Hello,

I need to confirm whether container HLXU8764321 has arrived at Felixstowe port.
Our haulier is ready to collect but we need confirmation before dispatching.

Thank you,
Anna Schmidt
Logistics EU GmbH',
'HLXU8764321', 'DRAFT_READY',
'Dear Ms Schmidt,

Thank you for contacting us regarding container HLXU8764321.

We are pleased to confirm that this container has arrived and is currently AT PORT at Felixstowe Terminal 2, Row D. Your haulier may proceed with collection arrangements.

Please ensure your haulier has the relevant documentation before arriving at the terminal.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours', 1),

('<MSG003@tradelink.co.uk>', 'peter.jones@tradelink.co.uk', 'CMAU5551234 customs clearance status',
'Hi,

Container CMAU5551234 was supposed to clear customs yesterday but we have not received any update.
Can you advise on the current situation?

Regards,
Peter Jones
TradeLink UK',
'CMAU5551234', 'DRAFT_READY',
'Dear Mr Jones,

Thank you for your patience regarding container CMAU5551234.

We can advise that this container is currently undergoing customs examination at HMRC Examination Bay 3. This process is expected to be completed by 12 April 2026 at 09:30. We recommend monitoring the HMRC portal for real-time updates.

We apologise for any inconvenience caused and will notify you as soon as the container is cleared.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '4 hours', NOW() - INTERVAL '3 hours', 1),

('<MSG004@globalship.net>', 'maria.kowalski@globalship.net', 'Request: Delivery confirmation OOLU9876543',
'Good morning,

Please confirm that container OOLU9876543 has been delivered to the final destination.
We need this for our records and invoice processing.

Many thanks,
Maria Kowalski
Global Shipping Partners',
'OOLU9876543', 'DRAFT_READY',
'Dear Ms Kowalski,

Thank you for your enquiry.

We can confirm that container OOLU9876543 has been successfully DELIVERED to DHL Depot, Birmingham on 5 April 2026 at 11:00. Your delivery confirmation reference is available upon request.

Please do not hesitate to contact us for the official delivery receipt.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '5 hours', NOW() - INTERVAL '4 hours', 1),

('<MSG005@nordfreight.dk>', 'lars.andersen@nordfreight.dk', 'ETA update needed - EVRU3219876',
'Dear Team,

Could you provide the latest ETA for container EVRU3219876?
We need to arrange warehouse space and labour for unloading.

Best regards,
Lars Andersen
Nord Freight Denmark',
'EVRU3219876', 'DRAFT_READY',
'Dear Mr Andersen,

Thank you for your enquiry regarding container EVRU3219876.

This container is currently IN TRANSIT aboard Vessel Ever Gentle with an ETA to Felixstowe of 25 April 2026 at 16:00. We recommend confirming labour and warehouse availability from 26 April onwards.

We will send you a pre-arrival notification 48 hours before arrival.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '6 hours', NOW() - INTERVAL '5 hours', 1),

('<MSG006@cargo-express.ie>', 'siobhan.murphy@cargo-express.ie', 'TEMU4448888 - ready for collection?',
'Hello,

Is container TEMU4448888 available for collection at Felixstowe?
Driver will be available tomorrow morning from 07:00.

Thanks,
Siobhan Murphy
Cargo Express Ireland',
'TEMU4448888', 'DRAFT_READY',
'Dear Ms Murphy,

Thank you for your message regarding container TEMU4448888.

We can confirm the container is AT PORT at Felixstowe Terminal 1, Row B and is available for collection. Your driver may collect from 08 April 2026 07:00 onwards. Please ensure the driver carries the collection reference and relevant documentation.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '7 hours', NOW() - INTERVAL '6 hours', 1),

('<MSG007@alpine-logistics.ch>', 'hans.mueller@alpine-logistics.ch', 'CAIU7773330 release status',
'Good afternoon,

We are waiting for container CAIU7773330 to be released for collection.
Could you advise on the current status and any outstanding requirements?

Kind regards,
Hans Mueller
Alpine Logistics Switzerland',
'CAIU7773330', 'DRAFT_READY',
'Dear Mr Mueller,

Thank you for contacting us regarding container CAIU7773330.

We can confirm this container has been RELEASED and is awaiting haulier collection as of 7 April 2026 at 10:00. There are no outstanding requirements. Your nominated haulier may collect at their earliest convenience.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '8 hours', NOW() - INTERVAL '7 hours', 1),

('<MSG008@iberia-cargo.es>', 'carlos.rodriguez@iberia-cargo.es', 'Follow up: container MSCU1234567 ETA',
'Hi,

Following up on my previous enquiry about MSCU1234567.
Has there been any change to the arrival schedule?

Thanks,
Carlos Rodriguez',
'MSCU1234567', 'DRAFT_READY',
'Dear Mr Rodriguez,

Thank you for following up on container MSCU1234567.

The schedule remains unchanged. The container is IN TRANSIT on Vessel MSC Francesca with ETA 20 April 2026 at 08:00. No delays have been reported at this time.

We will contact you immediately if there are any changes.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '9 hours', NOW() - INTERVAL '8 hours', 1),

('<MSG009@transbalt.lv>', 'janis.berzins@transbalt.lv', 'Urgent enquiry HLXU8764321 - customs docs',
'Dear Sir/Madam,

Container HLXU8764321 is at Felixstowe but we have not received customs clearance documents.
Please advise urgently as storage charges are accumulating.

Urgent regards,
Janis Berzins
TransBalt Latvia',
'HLXU8764321', 'DRAFT_READY',
'Dear Mr Berzins,

Thank you for your urgent message regarding container HLXU8764321.

We understand the urgency of your situation. The container is confirmed AT PORT at Felixstowe Terminal 2, Row D. Regarding customs documentation, please contact our documentation team directly at docs@smartfreight.com with your container reference. To minimise storage charges, we recommend contacting the terminal directly to arrange a short-term hold.

We are treating this as a priority matter.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '10 hours', NOW() - INTERVAL '9 hours', 1),

('<MSG010@oceanline.no>', 'ingrid.larsen@oceanline.no', 'Container CMAU5551234 - customs delay impact',
'Hello Smart Freight,

Our customer is asking about the customs delay for CMAU5551234.
What is the expected clearance date and will there be demurrage charges?

Best,
Ingrid Larsen
Ocean Line Norway',
'CMAU5551234', 'DRAFT_READY',
'Dear Ms Larsen,

Thank you for your message regarding container CMAU5551234.

The container remains under HMRC examination at Bay 3 with expected clearance by 12 April 2026. Regarding demurrage: free time allowance expires on 13 April. If clearance is achieved by 12 April, no demurrage should apply. We recommend raising a demurrage dispute preemptively with the shipping line as a precaution.

We will update you as soon as customs clearance is confirmed.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '11 hours', NOW() - INTERVAL '10 hours', 1),

-- NEW tickets (awaiting AI processing) --

('<MSG011@portlink.be>', 'frederik.de-smet@portlink.be', 'Status request: MSCU1234567',
'Hi,

Quick status check on MSCU1234567 please.

Thanks, Frederik',
'MSCU1234567', 'NEW', NULL, 0, false, NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes', 0),

('<MSG012@cargo-nl.nl>', 'willem.van-den-berg@cargo-nl.nl', 'Enquiry about HLXU8764321',
'Good day,

I would like to know if container HLXU8764321 is available for pick-up.
We have a slot at our warehouse tomorrow.

Willem van den Berg',
NULL, 'NEW', NULL, 0, false, NOW() - INTERVAL '25 minutes', NOW() - INTERVAL '25 minutes', 0),

('<MSG013@freight-hub.pl>', 'tomasz.wisniewski@freight-hub.pl', 'TEMU4448888 collection booking',
'Hello,

We need to book a collection slot for container TEMU4448888.
Please confirm availability for 9 April morning.

Regards,
Tomasz',
'TEMU4448888', 'NEW', NULL, 0, false, NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '20 minutes', 0),

('<MSG014@eurofreight.de>', 'klaudia.bauer@eurofreight.de', 'Container tracking EVRU3219876',
'Dear Smart Freight,

Could you send me the latest vessel position for container EVRU3219876?
We are preparing our import declaration.

Klaudia Bauer',
'EVRU3219876', 'NEW', NULL, 0, false, NOW() - INTERVAL '15 minutes', NOW() - INTERVAL '15 minutes', 0),

('<MSG015@uk-imports.co.uk>', 'james.taylor@uk-imports.co.uk', 'CAIU7773330 - haulier collection details',
'Hi there,

Our haulier needs the exact location of container CAIU7773330 for collection.
Can you provide terminal details and any special instructions?

James Taylor',
'CAIU7773330', 'NEW', NULL, 0, false, NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes', 0),

('<MSG016@balt-cargo.ee>', 'rain.tamm@balt-cargo.ee', 'Urgent: OOLU9876543 proof of delivery',
'Hello,

We urgently need a proof of delivery document for container OOLU9876543.
Our customer is disputing the delivery.

Rain Tamm
Baltic Cargo Estonia',
'OOLU9876543', 'NEW', NULL, 0, false, NOW() - INTERVAL '8 minutes', NOW() - INTERVAL '8 minutes', 0),

('<MSG017@med-shipping.it>', 'luigi.ferrari@med-shipping.it', 'Container number check - MSCU1234567',
'Buongiorno,

Please check the status of container number MSCU1234567.
I need this for my customer report.

Luigi Ferrari
Mediterranean Shipping Italy',
'MSCU1234567', 'NEW', NULL, 0, false, NOW() - INTERVAL '5 minutes', NOW() - INTERVAL '5 minutes', 0),

('<MSG018@trans-port.fr>', 'claire.dupont@trans-port.fr', 'HLXU8764321 - import VAT query',
'Dear team,

Container HLXU8764321 is at port. We have a query about import VAT calculation.
Could you advise on the customs value?

Claire Dupont
TransPort France',
'HLXU8764321', 'NEW', NULL, 0, false, NOW() - INTERVAL '3 minutes', NOW() - INTERVAL '3 minutes', 0),

('<MSG019@scandship.se>', 'erik.johansson@scandship.se', 'CMAU5551234 - alternative port query',
'Hello,

If CMAU5551234 is delayed in customs, is there an option to redirect to another port?
Please advise on the process.

Erik Johansson',
'CMAU5551234', 'NEW', NULL, 0, false, NOW() - INTERVAL '2 minutes', NOW() - INTERVAL '2 minutes', 0),

('<MSG020@adriatic-log.hr>', 'ana.horvat@adriatic-log.hr', 'Status of TEMU4448888',
'Dear Sir/Madam,

Please provide update on container TEMU4448888 as soon as possible.

Thank you,
Ana Horvat',
'TEMU4448888', 'NEW', NULL, 0, false, NOW() - INTERVAL '1 minute', NOW() - INTERVAL '1 minute', 0),

-- FAILED tickets --

('<MSG021@broken-sender.xyz>', 'malformed@sender.xyz', 'Re: container enquiry',
'Hi can you check container number ZZZZ0000000 for me? Not sure if this is right number.',
'ZZZZ0000000', 'FAILED', NULL, 3, false, NOW() - INTERVAL '5 hours', NOW() - INTERVAL '4 hours', 3),

('<MSG022@no-container.net>', 'no.container@enquiry.net', 'General freight question',
'Hello,

I have a general question about freight rates. No container number at this stage.
Could someone call me back?

Best',
NULL, 'FAILED', NULL, 3, false, NOW() - INTERVAL '6 hours', NOW() - INTERVAL '5 hours', 3),

('<MSG023@retry-test.com>', 'test@retry-test.com', 'Test message with invalid container',
'Please check container ABCD1234 (old format, 4 letters + 4 digits) for me.
I know the number might be wrong.',
'ABCD1234', 'FAILED', NULL, 3, false, NOW() - INTERVAL '7 hours', NOW() - INTERVAL '6 hours', 3),

-- CLOSED tickets --

('<MSG031@freightco.com>', 'john.harris@freightco.com', 'Resolved: MSCU1234567 ETA confirmed',
'Thank you for the update. We have informed our customer. This can be closed.

John',
'MSCU1234567', 'CLOSED',
'Dear Mr Harris,

Thank you for confirming. We are glad we could assist.
This ticket has been marked as resolved.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '12 hours', NOW() - INTERVAL '11 hours', 2),

('<MSG032@logistics-eu.com>', 'anna.schmidt@logistics-eu.com', 'Closed: HLXU8764321 collected',
'Container has been collected. Thank you for your support!

Anna',
'HLXU8764321', 'CLOSED',
'Dear Ms Schmidt,

Excellent news! We are pleased the collection went smoothly.
This ticket is now closed.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '13 hours', NOW() - INTERVAL '12 hours', 2),

('<MSG033@tradelink.co.uk>', 'peter.jones@tradelink.co.uk', 'OOLU9876543 - delivery confirmed OK',
'Delivery confirmed. Invoice has been processed. You can close this ticket.

Peter',
'OOLU9876543', 'CLOSED',
'Dear Mr Jones,

Thank you for confirming delivery. We have closed this ticket as resolved.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '14 hours', NOW() - INTERVAL '13 hours', 2),

-- More DRAFT_READY variety --

('<MSG041@portline.gr>', 'nikos.papadopoulos@portline.gr', 'EVRU3219876 pre-arrival check',
'Hello,

Container EVRU3219876 is approaching Felixstowe. Can you confirm berth arrangements
and pre-arrival documentation requirements?

Nikos Papadopoulos
Port Line Greece',
'EVRU3219876', 'DRAFT_READY',
'Dear Mr Papadopoulos,

Thank you for your pre-arrival enquiry for container EVRU3219876.

The vessel Ever Gentle is scheduled to berth at Felixstowe on 25 April 2026. Pre-arrival documentation should be submitted at least 24 hours before arrival. Required documents: Bill of Lading, Commercial Invoice, Packing List, Import Declaration (if applicable).

Please contact our pre-arrival desk at prearrivals@smartfreight.com for further assistance.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '90 minutes', NOW() - INTERVAL '60 minutes', 1),

('<MSG042@cargo-hu.hu>', 'zoltan.nagy@cargo-hu.hu', 'CAIU7773330 - storage question',
'Hi,

Is there any storage charge for container CAIU7773330?
It has been released but our haulier cannot collect until next week.

Zoltan Nagy',
'CAIU7773330', 'DRAFT_READY',
'Dear Mr Nagy,

Thank you for your message regarding container CAIU7773330.

The container is currently RELEASED and awaiting collection. Please be aware that free storage time at the terminal may be limited. We strongly recommend contacting Felixstowe Terminal directly to confirm storage terms and arrange a collection date to avoid any demurrage or storage charges.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '100 minutes', NOW() - INTERVAL '80 minutes', 1),

('<MSG043@atlantic-freight.pt>', 'sofia.ferreira@atlantic-freight.pt', 'MSCU1234567 - cargo insurance query',
'Dear Smart Freight,

Our insurance requires confirmation that container MSCU1234567 is insured during transit.
Can you provide a transit insurance certificate or advise?

Sofia Ferreira
Atlantic Freight Portugal',
'MSCU1234567', 'DRAFT_READY',
'Dear Ms Ferreira,

Thank you for your cargo insurance enquiry regarding container MSCU1234567.

Container MSCU1234567 is currently IN TRANSIT on Vessel MSC Francesca. Regarding cargo insurance: insurance arrangements are typically the responsibility of the cargo owner. We recommend contacting your freight forwarder or insurance broker for a transit insurance certificate. We can provide a cargo manifest if required for insurance purposes.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '110 minutes', NOW() - INTERVAL '90 minutes', 1),

('<MSG044@danube-log.ro>', 'mihai.ionescu@danube-log.ro', 'HLXU8764321 - reefer check',
'Hello,

Container HLXU8764321 contains temperature-sensitive cargo.
Can you confirm the reefer plug availability at the terminal?

Mihai Ionescu
Danube Logistics Romania',
'HLXU8764321', 'DRAFT_READY',
'Dear Mr Ionescu,

Thank you for your reefer enquiry regarding container HLXU8764321.

The container is AT PORT at Felixstowe Terminal 2, Row D. For reefer plug availability and temperature monitoring, please contact Felixstowe Terminal directly at terminal-ops@felixstowe.co.uk, quoting the container number and your reefer settings. We recommend doing this as soon as possible to ensure continuous cold chain compliance.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '120 minutes', NOW() - INTERVAL '100 minutes', 1),

('<MSG045@arctic-log.fi>', 'paavo.virtanen@arctic-log.fi', 'Status TEMU4448888 - overweight concern',
'Dear team,

We have received notification that container TEMU4448888 may be overweight.
Can you confirm the VGM (Verified Gross Mass) on file?

Paavo Virtanen
Arctic Logistics Finland',
'TEMU4448888', 'DRAFT_READY',
'Dear Mr Virtanen,

Thank you for raising the VGM concern for container TEMU4448888.

The container is currently AT PORT at Felixstowe Terminal 1, Row B. To obtain the VGM certificate on file, please contact our documentation team at docs@smartfreight.com, quoting the container number and Bill of Lading reference. If the container is confirmed overweight, the terminal will advise on the restuffing or weight adjustment process.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '130 minutes', NOW() - INTERVAL '110 minutes', 1),

('<MSG046@vistula-freight.pl>', 'piotr.kaminski@vistula-freight.pl', 'CMAU5551234 - customs agent query',
'Hello,

We have appointed a new customs agent for CMAU5551234.
How do we update the customs agent details on file?

Piotr Kaminski
Vistula Freight Poland',
'CMAU5551234', 'DRAFT_READY',
'Dear Mr Kaminski,

Thank you for notifying us of the customs agent change for container CMAU5551234.

To update the nominated customs agent, please send the new agent''s details (company name, EORI number, contact details) to our customs team at customs@smartfreight.com. Please include the container number and Bill of Lading reference. This will be processed within 2 business hours.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '140 minutes', NOW() - INTERVAL '120 minutes', 1),

('<MSG047@balkan-ship.rs>', 'dragan.jovanovic@balkan-ship.rs', 'OOLU9876543 - late delivery claim',
'Dear Smart Freight,

Container OOLU9876543 was delivered 3 days late. We wish to file a late delivery claim.
Please advise the process.

Dragan Jovanovic
Balkan Shipping Serbia',
'OOLU9876543', 'DRAFT_READY',
'Dear Mr Jovanovic,

Thank you for contacting us regarding the late delivery of container OOLU9876543.

We understand your frustration. Container OOLU9876543 was delivered to DHL Depot, Birmingham on 5 April 2026. To file a late delivery claim, please submit the following to claims@smartfreight.com: your original Bill of Lading, the agreed delivery date, evidence of losses incurred due to the delay, and a formal claim letter. Our claims team will respond within 5 business days.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '150 minutes', NOW() - INTERVAL '130 minutes', 1),

('<MSG048@westport.ie>', 'ciara.obrien@westport.ie', 'EVRU3219876 - change of delivery address',
'Hello,

We need to change the delivery address for container EVRU3219876.
The original consignee has moved premises.

Ciara O''Brien
Westport Logistics Ireland',
'EVRU3219876', 'DRAFT_READY',
'Dear Ms O''Brien,

Thank you for notifying us of the address change for container EVRU3219876.

As the container is still IN TRANSIT on Vessel Ever Gentle (ETA 25 April 2026), there is still time to process a delivery address amendment. Please send the updated consignee details to amendments@smartfreight.com immediately, including the original and new delivery addresses. Note that an amendment fee may apply depending on the shipping line''s terms.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '160 minutes', NOW() - INTERVAL '140 minutes', 1),

('<MSG049@adriaship.it>', 'marco.bianchi@adriaship.it', 'CAIU7773330 - container condition report',
'Hi,

Before our haulier collects CAIU7773330, we would like a condition report.
Is there any damage noted on the container?

Marco Bianchi
Adria Ship Italy',
'CAIU7773330', 'DRAFT_READY',
'Dear Mr Bianchi,

Thank you for your enquiry regarding the condition of container CAIU7773330.

To obtain an Equipment Interchange Report (EIR), please contact the terminal directly at Felixstowe and request an EIR for container CAIU7773330 when your haulier arrives for collection. Any pre-existing damage will be noted on the EIR. If you require a pre-collection survey, this must be arranged with the terminal in advance.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '170 minutes', NOW() - INTERVAL '150 minutes', 1),

('<MSG050@nordiclog.dk>', 'mette.christensen@nordiclog.dk', 'HLXU8764321 - shipper seals intact?',
'Dear team,

Can you confirm the shipper seals on container HLXU8764321 are intact?
This is a requirement from our buyer.

Mette Christensen
Nordic Logistics Denmark',
'HLXU8764321', 'DRAFT_READY',
'Dear Ms Christensen,

Thank you for your seal integrity enquiry for container HLXU8764321.

Container HLXU8764321 is AT PORT at Felixstowe Terminal 2, Row D. Seal verification is carried out at the time of gate-in at the terminal. To obtain the seal numbers on record, please request an Equipment Interchange Report from the terminal. If the seals were broken during customs inspection, an HMRC seal will have been applied in their place.

Kind regards,
Smart Freight Inbox Team',
0, false, NOW() - INTERVAL '180 minutes', NOW() - INTERVAL '160 minutes', 1);
