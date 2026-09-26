using { kaizen as db } from '../db/schema';
using ManageService as s from '../srv/manage-service';

// ---- labels (on the tables, so every service and app shows the same words) ----
annotate db.Kaizens with {
  number           @title: 'Number';
  title            @title: 'Title';
  problem          @title: 'Problem'          @UI.MultiLineText;
  rootCause        @title: 'Root cause'       @UI.MultiLineText;
  countermeasure   @title: 'Countermeasure'   @UI.MultiLineText;
  wasteType        @title: 'Waste type';
  pillar           @title: 'Pillar'           @Common.Text: pillar.name  @Common.TextArrangement: #TextFirst;
  isSafety         @title: 'Safety issue';
  estimatedBenefit @title: 'Estimated saving (EUR/yr)';
  status           @title: 'Status'           @Common.Text: status.name  @Common.TextArrangement: #TextOnly;
  step             @title: 'Approval step';
  nextRole         @title: 'Next step';
  route            @title: 'Approval route';
  equipment        @title: 'Machine'          @Common.Text: equipment.name @Common.TextArrangement: #TextFirst;
  plant            @title: 'Plant'            @Common.Text: plant.name   @Common.TextArrangement: #TextFirst;
  owner            @title: 'Owner';
  dueDate          @title: 'Due date';
  closedAt         @title: 'Closed at';
  createdBy        @title: 'Created by';
  createdAt        @title: 'Created at';
  aiDrafted        @title: 'Drafted with AI';
  fiveWhy          @title: '5-Why analysis'   @UI.MultiLineText;
  similarTo        @title: 'Possible duplicate of' @Common.Text: similarTo.title @Common.TextArrangement: #TextLast;
  similarity       @title: 'Similarity';
  pmNotification   @title: 'S/4 notification';
}
annotate db.Photos with {
  kind @title: 'Kind'; content @title: 'Photo'; createdBy @title: 'By'; createdAt @title: 'Taken at';
}
annotate db.Tasks with {
  title @title: 'Task'; owner @title: 'Owner'; dueDate @title: 'Due date'; done @title: 'Done';
}
annotate db.Benefits with {
  type @title: 'Type'; baseline @title: 'Baseline'; improved @title: 'Improved'; unit @title: 'Unit';
  annualSaving @title: 'Saving (EUR/yr)'; verified @title: 'Verified by CI';
}
annotate db.StatusHistory with {
  fromStatus @title: 'From'; toStatus @title: 'To'; note @title: 'Note'; createdBy @title: 'By'; createdAt @title: 'When';
}
annotate db.Equipment with { ID @title: 'Machine'; name @title: 'Name'; }
annotate db.Plants with { ID @title: 'Plant'; name @title: 'Name'; }

// machine value help: pick from the equipment list, show its name
annotate s.Kaizens with {
  equipment @Common.ValueList: { CollectionPath: 'Equipment', Parameters: [
    { $Type: 'Common.ValueListParameterInOut', LocalDataProperty: equipment_ID, ValueListProperty: 'ID' },
    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'name' }
  ] };
  plant @Common.ValueList: { CollectionPath: 'Plants', Parameters: [
    { $Type: 'Common.ValueListParameterInOut', LocalDataProperty: plant_ID, ValueListProperty: 'ID' },
    { $Type: 'Common.ValueListParameterDisplayOnly', ValueListProperty: 'name' }
  ] };
}

// ---- Manage Kaizens: list with "Waiting for me" and "All kaizens" tabs ----
// kaizens are never deleted from the app (audit trail); an admin can still do it through the API
annotate s.Kaizens with @UI.DeleteHidden;

annotate s.Kaizens with @(
  UI.HeaderInfo: { TypeName: 'Kaizen', TypeNamePlural: 'Kaizens', Title: { Value: title, ![@UI.Importance]: #High }, Description: { Value: number } },
  UI.SelectionFields: [ status_code, plant_ID, pillar_code, equipment_ID, isSafety ],
  UI.LineItem: [
    { Value: number, ![@UI.Importance]: #High }, { Value: title, ![@UI.Importance]: #High }, { Value: status_code, ![@UI.Importance]: #High }, { Value: nextRole, ![@UI.Importance]: #High }, { Value: plant_ID, ![@UI.Importance]: #High }, { Value: pillar_code, ![@UI.Importance]: #High },
    { Value: equipment_ID, ![@UI.Importance]: #High }, { Value: isSafety, ![@UI.Importance]: #High }, { Value: estimatedBenefit, ![@UI.Importance]: #High }, { Value: createdBy }, { Value: createdAt },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.EntityContainer/syncEquipment', Label: 'Sync machines from S/4HANA' }
  ],
  UI.PresentationVariant: { SortOrder: [{ Property: number, Descending: true }], Visualizations: ['@UI.LineItem'] },
  UI.SelectionPresentationVariant #inbox: {
    Text: 'Waiting for me',
    SelectionVariant: { SelectOptions: [{ PropertyName: waitingForMe, Ranges: [{ Sign: #I, Option: #EQ, Low: true }] }] },
    PresentationVariant: { SortOrder: [{ Property: createdAt }], Visualizations: ['@UI.LineItem'] }
  },
  UI.SelectionPresentationVariant #all: {
    Text: 'All kaizens',
    SelectionVariant: { SelectOptions: [] },
    PresentationVariant: { SortOrder: [{ Property: number, Descending: true }], Visualizations: ['@UI.LineItem'] }
  },
  // object page header: workflow buttons, shown only when the user may run them now
  UI.Identification: [
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.approve', Label: 'Approve' },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.reject', Label: 'Reject' },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.start', Label: 'Start' },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.requestVerification', Label: 'Request verification' },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.close', Label: 'Close kaizen' },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.fiveWhy', Label: '✨ 5-Why analysis' },
    { $Type: 'UI.DataFieldForAction', Action: 'ManageService.generateA3', Label: '✨ Generate A3' }
  ],
  UI.HeaderFacets: [{ $Type: 'UI.ReferenceFacet', Target: '@UI.FieldGroup#Workflow' }],
  UI.FieldGroup #Workflow: { Data: [{ Value: status_code, ![@UI.Importance]: #High }, { Value: nextRole, ![@UI.Importance]: #High }, { Value: owner }, { Value: dueDate }] },
  UI.FieldGroup #Details: { Data: [
    { Value: title }, { Value: problem }, { Value: rootCause }, { Value: countermeasure }, { Value: wasteType },
    { Value: pillar_code }, { Value: isSafety }, { Value: estimatedBenefit }, { Value: aiDrafted }, { Value: similarTo_ID }, { Value: similarity }
  ] },
  UI.FieldGroup #Analysis: { Data: [
    { Value: fiveWhy }, { Value: rootCause },
    { $Type: 'UI.DataFieldWithUrl', Value: 'Open printable A3 report', Url: a3Url, Label: 'A3 report' }
  ] },
  UI.FieldGroup #Where: { Data: [{ Value: equipment_ID }, { Value: plant_ID }, { Value: createdBy }, { Value: createdAt }, { Value: closedAt }] },
  UI.Facets: [
    { $Type: 'UI.ReferenceFacet', ID: 'details', Label: 'Details', Target: '@UI.FieldGroup#Details' },
    { $Type: 'UI.ReferenceFacet', ID: 'where', Label: 'Machine', Target: '@UI.FieldGroup#Where' },
    { $Type: 'UI.ReferenceFacet', ID: 'analysis', Label: 'Analysis (AI assisted)', Target: '@UI.FieldGroup#Analysis' },
    { $Type: 'UI.ReferenceFacet', ID: 'photos', Label: 'Photos', Target: 'photos/@UI.LineItem' },
    { $Type: 'UI.ReferenceFacet', ID: 'tasks', Label: 'Tasks', Target: 'tasks/@UI.LineItem' },
    { $Type: 'UI.ReferenceFacet', ID: 'benefits', Label: 'Benefits', Target: 'benefits/@UI.LineItem' },
    { $Type: 'UI.ReferenceFacet', ID: 'history', Label: 'History', Target: 'history/@UI.LineItem' }
  ]
);

annotate s.Kaizens with actions {
  approve @Core.OperationAvailable: { $edmJson: { $Path: 'in/canApprove' } }
          @Common.SideEffects: { TargetProperties: ['in/*'], TargetEntities: ['in/history'] };
  reject  @Core.OperationAvailable: { $edmJson: { $Path: 'in/canApprove' } }
          @Common.SideEffects: { TargetProperties: ['in/*'], TargetEntities: ['in/history'] };
  start   @Core.OperationAvailable: { $edmJson: { $Path: 'in/canStart' } }
          @Common.SideEffects: { TargetProperties: ['in/*'], TargetEntities: ['in/history'] };
  requestVerification @Core.OperationAvailable: { $edmJson: { $Path: 'in/canRequestVerification' } }
          @Common.SideEffects: { TargetProperties: ['in/*'], TargetEntities: ['in/history'] };
  close   @Core.OperationAvailable: { $edmJson: { $Path: 'in/canClose' } }
          @Common.SideEffects: { TargetProperties: ['in/*'], TargetEntities: ['in/history'] };
  fiveWhy @Core.OperationAvailable: { $edmJson: { $Path: 'in/canAnalyze' } }
          @Common.SideEffects: { TargetProperties: ['in/fiveWhy', 'in/rootCause'] };
  generateA3 @Common.SideEffects: { TargetProperties: ['in/a3'] };
}


annotate s.Photos with { url @title: 'Photo' @UI.IsImageURL; };
annotate s.Photos with @(
  UI.LineItem: [{ Value: url }, { Value: kind }, { Value: createdBy }, { Value: createdAt }],
  UI.PresentationVariant: { SortOrder: [{ Property: createdAt }], Visualizations: ['@UI.LineItem'] }
);
annotate s.Tasks with @(
  UI.HeaderInfo: { TypeName: 'Task', TypeNamePlural: 'Tasks', Title: { Value: title } },
  UI.LineItem: [{ Value: title, ![@UI.Importance]: #High }, { Value: owner }, { Value: dueDate }, { Value: done }]
);
annotate s.Benefits with @(
  UI.HeaderInfo: { TypeName: 'Benefit', TypeNamePlural: 'Benefits', Title: { Value: type } },
  UI.LineItem: [{ Value: type }, { Value: baseline }, { Value: improved }, { Value: unit }, { Value: annualSaving }, { Value: verified }]
);
annotate s.StatusHistory with @(
  UI.LineItem: [{ Value: fromStatus }, { Value: toStatus }, { Value: note }, { Value: createdBy }, { Value: createdAt }],
  UI.PresentationVariant: { SortOrder: [{ Property: createdAt }], Visualizations: ['@UI.LineItem'] }
);

// ---- KPIs ----
annotate s.Kpis with @(
  UI.HeaderInfo: { TypeName: 'KPI', TypeNamePlural: 'Kaizen KPIs by plant and pillar' },
  UI.SelectionFields: [],
  UI.LineItem: [
    { Value: plantName, Label: 'Plant' }, { Value: pillarName, Label: 'Pillar' }, { Value: kaizens, Label: 'Kaizens' },
    { Value: open, Label: 'Open' }, { Value: closed, Label: 'Closed' }, { Value: avgCycleDays, Label: 'Avg cycle time (days)' },
    { Value: verifiedSaving, Label: 'Verified saving (EUR/yr)' }
  ]
);

annotate s.Tasks with @(
  UI.FieldGroup #Main: { Data: [{ Value: title, ![@UI.Importance]: #High }, { Value: owner }, { Value: dueDate }, { Value: done }] },
  UI.Facets: [{ $Type: 'UI.ReferenceFacet', Label: 'Task', Target: '@UI.FieldGroup#Main' }]
);
annotate s.Benefits with @(
  UI.FieldGroup #Main: { Data: [{ Value: type }, { Value: baseline }, { Value: improved }, { Value: unit }, { Value: annualSaving }, { Value: verified }] },
  UI.Facets: [{ $Type: 'UI.ReferenceFacet', Label: 'Benefit', Target: '@UI.FieldGroup#Main' }]
);
