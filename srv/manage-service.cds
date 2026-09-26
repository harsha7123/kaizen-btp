using { kaizen as db } from '../db/schema';

// Fiori Elements backend for supervisors, CI / plant managers and EHS. Same tables and business rules as
// KaizenService (the phone app's non-draft API); drafts here only cover the kaizen text, tasks and benefits.
// Photos and history are read-only associations, so saving a draft can never touch photo bytes or the audit trail.
@path: '/odata/v4/manage'
@requires: ['Supervisor', 'CIManager', 'PlantManager', 'EHS', 'Admin']
service ManageService {

  @odata.draft.enabled
  @restrict: [
    { grant: 'READ', to: 'authenticated-user' },
    { grant: ['CREATE', 'UPDATE'], to: ['Supervisor', 'CIManager', 'PlantManager', 'EHS'] },
    { grant: ['approve', 'reject'], to: ['Supervisor', 'CIManager', 'PlantManager', 'EHS'] },
    { grant: 'start', to: ['CIManager', 'PlantManager'] },
    { grant: 'requestVerification', to: 'authenticated-user' },
    { grant: 'close', to: ['CIManager', 'PlantManager'] },
    { grant: ['fiveWhy', 'generateA3'], to: ['Supervisor', 'CIManager', 'PlantManager', 'EHS'] },
    { grant: '*', to: 'Admin' }
  ]
  entity Kaizens as projection on db.Kaizens {
    *,
    photos  : Association to many Photos on photos.kaizen = $self,
    history : Association to many StatusHistory on history.kaizen = $self,
    // filter only: true = next step is one of my roles, or I own it while in progress (rewritten in the handler)
    virtual waitingForMe : Boolean,
    // which actions the current user may run now (hides buttons that would only fail)
    virtual canApprove : Boolean,
    virtual canStart   : Boolean,
    virtual canRequestVerification : Boolean,
    virtual canClose   : Boolean,
    virtual canAnalyze : Boolean,
    // printable A3 report page (app/a3)
    '/a3/index.html?ID=' || ID as a3Url : String(80)
  } excluding { photos, history } actions {
    action approve(note : String @title: 'Note') returns Kaizens;
    action reject(note : String @mandatory @title: 'Reason') returns Kaizens;
    action start(owner : String @mandatory @title: 'Owner (user ID)', dueDate : Date @title: 'Due date') returns Kaizens;
    action requestVerification() returns Kaizens;
    action close(note : String @title: 'Note') returns Kaizens;
    // AI assist: fills fiveWhy (and rootCause if empty) / the A3 report
    action fiveWhy() returns Kaizens;
    action generateA3() returns Kaizens;
  };

  entity Tasks    as projection on db.Tasks;
  entity Benefits as projection on db.Benefits;

  @readonly entity Photos        as projection on db.Photos { *, '/odata/v4/manage/Photos(' || ID || ')/content' as url : String(120) };
  @readonly entity StatusHistory as projection on db.StatusHistory;


  // KPIs by plant and pillar, computed in the handler (count, open/closed, cycle time, verified savings)
  @readonly @cds.persistence.skip
  entity Kpis {
    key plant       : String(10);
    key pillar      : String(4);
        plantName   : String(80);
        pillarName  : String(80);
        kaizens     : Integer;
        open        : Integer;
        closed      : Integer;
        avgCycleDays : Decimal(9, 1);
        verifiedSaving : Decimal(15, 2);
  }

  @readonly entity Plants      as projection on db.Plants;
  @readonly entity WorkCenters as projection on db.WorkCenters;
  @readonly entity Equipment   as projection on db.Equipment;
  @readonly entity Pillars     as projection on db.Pillars;
  @readonly entity Statuses    as projection on db.Statuses;
}
