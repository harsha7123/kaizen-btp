using { kaizen as db } from '../db/schema';

@path: '/odata/v4/kaizen'
@requires: 'authenticated-user'
service KaizenService {

  @restrict: [
    { grant: 'READ', to: 'authenticated-user' },
    { grant: 'CREATE', to: ['Operator', 'Supervisor', 'CIManager', 'PlantManager'] },
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

  @restrict: [
    { grant: ['READ', 'CREATE', 'UPDATE'], to: 'authenticated-user' },
    { grant: '*', to: ['CIManager', 'PlantManager', 'Admin'] }
  ]
  entity Photos        as projection on db.Photos;
  @restrict: [
    { grant: ['READ', 'CREATE', 'UPDATE'], to: 'authenticated-user' },
    { grant: '*', to: ['CIManager', 'PlantManager', 'Admin'] }
  ]
  entity Tasks         as projection on db.Tasks;
  @restrict: [
    { grant: ['READ', 'CREATE', 'UPDATE'], to: 'authenticated-user' },
    { grant: '*', to: ['CIManager', 'PlantManager', 'Admin'] }
  ]
  entity Benefits      as projection on db.Benefits;
  @readonly entity StatusHistory as projection on db.StatusHistory;

  @readonly entity Plants      as projection on db.Plants;
  @readonly entity WorkCenters as projection on db.WorkCenters;
  @readonly entity Equipment   as projection on db.Equipment;
  @readonly entity Pillars     as projection on db.Pillars;
  @readonly entity Statuses    as projection on db.Statuses;

  @restrict: [{ grant: 'READ', to: 'authenticated-user' }, { grant: '*', to: 'Admin' }]
  entity WorkflowRoutes as projection on db.WorkflowRoutes;
}
