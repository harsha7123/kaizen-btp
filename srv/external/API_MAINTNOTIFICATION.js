import cds from '@sap/cds'

// Local mock only (cds watch / tests with --with-mocks): S/4 assigns notification numbers, so the mock does too.
export default class API_MAINTNOTIFICATION extends cds.ApplicationService {
  init () {
    this.before('CREATE', 'MaintenanceNotification', async req => {
      const { MaintenanceNotification: N } = this.entities
      const { max } = await SELECT.one.from(N).columns`max(MaintenanceNotification) as max`
      req.data.MaintenanceNotification ??= String((max ? +max : 10000000) + 1)
    })
    return super.init()
  }
}
