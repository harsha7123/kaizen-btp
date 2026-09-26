using { kaizen as db } from '../db/schema';

@path: '/odata/v4/kaizen'
@requires: 'authenticated-user'
service KaizenService {

  @restrict: [
    { grant: 'READ', to: 'authenticated-user' },
    { grant: 'CREATE', to: ['Operator', 'Supervisor', 'CIManager', 'PlantManager', 'EHS'] },
    { grant: 'UPDATE', to: 'Operator', where: 'createdBy = $user' },
    { grant: 'UPDATE', to: ['Supervisor', 'CIManager', 'PlantManager'] },
    { grant: ['approve', 'reject'], to: ['Supervisor', 'CIManager', 'PlantManager', 'EHS'] },
    { grant: 'start', to: ['CIManager', 'PlantManager'] },
    { grant: 'requestVerification', to: 'authenticated-user' },
    { grant: 'close', to: ['CIManager', 'PlantManager'] },
    { grant: '*', to: 'Admin' }
  ]
  entity Kaizens as projection on db.Kaizens actions {
    action approve(note : String) returns Kaizens;
    action reject(note : String @mandatory) returns Kaizens;
    action start(owner : String @mandatory, dueDate : Date) returns Kaizens;
    action requestVerification() returns Kaizens;
    action close(note : String) returns Kaizens;
  };

  // photos: anyone adds to kaizens they may touch (checked in the handler); only the uploader replaces the bytes
  @restrict: [
    { grant: ['READ', 'CREATE'], to: 'authenticated-user' },
    { grant: 'UPDATE', to: 'authenticated-user', where: 'createdBy = $user' },
    { grant: '*', to: ['CIManager', 'PlantManager', 'Admin'] }
  ]
  entity Photos        as projection on db.Photos;
  // tasks: managers plan them; the task owner may tick them off
  @restrict: [
    { grant: 'READ', to: 'authenticated-user' },
    { grant: 'UPDATE', to: 'authenticated-user', where: 'owner = $user' },
    { grant: ['CREATE', 'UPDATE'], to: ['Supervisor', 'EHS'] },
    { grant: '*', to: ['CIManager', 'PlantManager', 'Admin'] }
  ]
  entity Tasks         as projection on db.Tasks;
  // benefits: managers record them; only CI / plant managers verify (checked in the handler)
  @restrict: [
    { grant: 'READ', to: 'authenticated-user' },
    { grant: ['CREATE', 'UPDATE'], to: ['Supervisor', 'EHS'] },
    { grant: '*', to: ['CIManager', 'PlantManager', 'Admin'] }
  ]
  entity Benefits      as projection on db.Benefits;
  @readonly entity StatusHistory as projection on db.StatusHistory;

  // ---- AI assist (online only; the phone keeps working without it) ----
  type DraftSuggestion {
    title : String(120); problem : String(2000); pillar_code : String(4); wasteType : String(30); isSafety : Boolean; provider : String(10);
  }
  type SimilarKaizen { ID : UUID; number : String(20); title : String(120); status : String(20); score : Decimal(3, 2); }
  // image: base64 JPEG, shrunk on the phone (768 px)
  action draftFromPhoto(image : LargeString, equipment_ID : String(20), hint : String(120)) returns DraftSuggestion;
  function similar(title : String(120), problem : String(2000), equipment_ID : String(20)) returns many SimilarKaizen;

  @readonly entity Plants      as projection on db.Plants;
  @readonly entity WorkCenters as projection on db.WorkCenters;
  @readonly entity Equipment   as projection on db.Equipment;
  @readonly entity Pillars     as projection on db.Pillars;
  @readonly entity Statuses    as projection on db.Statuses;

  @restrict: [{ grant: 'READ', to: 'authenticated-user' }, { grant: '*', to: 'Admin' }]
  entity WorkflowRoutes as projection on db.WorkflowRoutes;
}
