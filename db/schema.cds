namespace kaizen;

using { cuid, managed, sap.common.CodeList } from '@sap/cds/common';

// ---- Master data (demo: seeded CSV; product: cached from S/4HANA APIs) ----
entity Plants {
  key ID   : String(10);
      name : String(80);
}

entity WorkCenters {
  key ID    : String(10);
      name  : String(80);
      plant : Association to Plants;
}

entity Equipment {
  key ID                 : String(20); // matches the QR label on the machine
      name               : String(80);
      functionalLocation : String(40);
      workCenter         : Association to WorkCenters;
      plant              : Association to Plants;
}

// ---- Code lists ----
entity Pillars : CodeList {
  key code : String(4); // FI, AM, PM, QM, EEM, TE, SHE, OT
}

entity Statuses : CodeList {
  key code : String(20);
}

// ---- Kaizen core ----
entity Kaizens : cuid, managed {
  number      : String(20) @readonly; // KAI-2026-0001, per tenant
  title       : String(120) @mandatory;
  problem     : String(2000);
  rootCause   : String(2000);
  countermeasure : String(2000);
  wasteType   : String(30);
  pillar      : Association to Pillars @mandatory;
  isSafety    : Boolean default false;
  estimatedBenefit : Decimal(15, 2); // EUR/year, drives routing threshold
  status      : Association to Statuses default 'Submitted' @readonly;
  step        : Integer default 0 @readonly; // index into route.approvers
  nextRole    : String(20) @readonly; // who acts next: an approver role, 'CIManager' (start/close) or 'Owner'; drives the inbox
  closedAt    : Timestamp @readonly; // for cycle time KPIs
  // AI assist (Phase 4): written by actions only; aiDrafted is set by the phone when the operator used "Draft with AI"
  aiDrafted   : Boolean default false;
  fiveWhy     : LargeString @readonly;
  a3          : LargeString @readonly; // JSON: background, currentCondition, goal, rootCause, countermeasures, results, followUp
  similarTo   : Association to Kaizens @readonly; // best match from the duplicate check at creation
  similarity  : Decimal(3, 2) @readonly;
  route       : Association to WorkflowRoutes @readonly;
  equipment   : Association to Equipment;
  plant       : Association to Plants; // derived from equipment when scanned
  owner       : String(255);
  dueDate     : Date;
  photos      : Composition of many Photos on photos.kaizen = $self;
  tasks       : Composition of many Tasks on tasks.kaizen = $self;
  benefits    : Composition of many Benefits on benefits.kaizen = $self;
  history     : Composition of many StatusHistory on history.kaizen = $self;
}

entity Photos : cuid, managed {
  kaizen    : Association to Kaizens;
  @assert.range kind : String(6) enum { Before; After } default 'Before';
  // ponytail: HANA BLOB for the trial demo; move to Object Store when photo volume matters
  content   : LargeBinary @Core.MediaType: mediaType @Core.AcceptableMediaTypes: ['image/jpeg', 'image/png', 'image/webp'];
  mediaType : String(40) @Core.IsMediaType default 'image/jpeg';
}

entity Tasks : cuid, managed {
  kaizen  : Association to Kaizens;
  title   : String(120) @mandatory;
  owner   : String(255);
  dueDate : Date;
  done    : Boolean default false;
}

entity Benefits : cuid, managed {
  kaizen        : Association to Kaizens;
  @mandatory @assert.range type : String(10) enum { Time; Cost; Quality; Safety; Energy; Space; OEE };
  baseline      : Decimal(15, 2);
  improved      : Decimal(15, 2);
  unit          : String(20);
  annualSaving  : Decimal(15, 2); // EUR
  verified      : Boolean default false;
}

entity StatusHistory : cuid, managed {
  kaizen : Association to Kaizens;
  fromStatus : String(20);
  toStatus   : String(20);
  note   : String(500);
}

// ---- Table-driven approval routing (per plant / pillar / benefit threshold) ----
entity WorkflowRoutes : cuid {
  plant      : Association to Plants;     // null = any plant
  pillar     : Association to Pillars;    // null = any pillar
  maxBenefit : Decimal(15, 2);            // null = no limit
  approvers  : String(200) @mandatory;    // ordered roles, e.g. "Supervisor,CIManager"
  priority   : Integer default 100;       // lower wins
}
