// SAP S/4HANA Cloud "Equipment" API (OData V2), only the fields this app reads.
// Replace with the full definition from `cds import` once the sandbox API key is available (same names).
@cds.external
service API_EQUIPMENT {
  entity Equipment {
    key Equipment           : String(18);
    key ValidityEndDate     : Date;
        EquipmentName       : String(40);
        FunctionalLocation  : String(40);
        MaintenancePlant    : String(4);
        MainWorkCenter      : String(8);
        MainWorkCenterPlant : String(4);
  }
}
