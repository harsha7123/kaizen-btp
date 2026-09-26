// SAP S/4HANA Cloud "Maintenance Notification" API (OData V2), only the fields this app writes.
@cds.external
service API_MAINTNOTIFICATION {
  entity MaintenanceNotification {
    key MaintenanceNotification   : String(12);
        NotificationText          : String(40);
        NotificationType          : String(2);
        TechnicalObject           : String(40);
        TechObjIsEquipOrFuncnlLoc : String(10);
        MaintPriority             : String(1);
        MaintNotifLongTextForEdit : LargeString;
  }
}
