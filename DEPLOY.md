# Deploy the Kaizen demo to the BTP Trial

Everything runs in your free trial (region us10, org `4143d2d3trial`, space `dev`). About 45 minutes the first time.
What gets created: 3 apps (`kaizen-btp` app router, `kaizen-btp-srv`, `kaizen-btp-mtx`) and 4 services
(login `kaizen-btp-auth`, per-customer databases `kaizen-btp-db`, SaaS registry `kaizen-btp-registry`, destinations `kaizen-btp-destination`).

## 0. Once: tools and HANA

1. Install `make` (the MTA build tool needs it on Windows), then open a **new** terminal:
   ```
   winget install ezwinports.make
   ```
2. Start `kaizen-hana` in HANA Cloud Central (it stops every night) and wait for **Running**.
3. Check you are logged in: `cf target` shows org `4143d2d3trial`, space `dev`. If not: `cf login -a https://api.cf.us10-001.hana.ondemand.com`.

## 1. Build and deploy (10-15 min)

In the project folder:
```
mbt build
cf deploy mta_archives/kaizen-btp_1.0.0.mtar
```
Done when it prints `Process finished.` Check: `cf apps` shows the three apps as `started`.

## 2. Customer 1 = your trial subaccount

1. Cockpit → subaccount **trial** → **Overview**: note the **Subdomain** (e.g. `4143d2d3trial`).
2. **Services → Instances and Subscriptions → Create** → Service **Kaizen** → Plan **default** → Create. Wait for **Subscribed**.
3. Give the app its address for this customer (trial does not allow wildcard routes):
   ```
   cf map-route kaizen-btp cfapps.us10-001.hana.ondemand.com --hostname <subdomain>-4143d2d3trial-dev-kaizen-btp
   ```
   (Check the exact app host with `cf app kaizen-btp`, the part after the dash is the same for every customer.)
4. **Security → Users** → your user → **Assign Role Collection** → select all six **Kaizen …** collections (for the demo you play every role).
   Other people: add their e-mail under Users (they need a free SAP ID) and assign only their role.
5. Log out of the cockpit and back in, then open `https://<subdomain>-4143d2d3trial-dev-kaizen-btp.cfapps.us10-001.hana.ondemand.com`
   → SAP login → the Kaizen start page. Run the demo script from `readme.md` / PLAN.md.

## 3. Customer 2 = a second subaccount (proves isolated data)

1. Cockpit → **Account Explorer → Create → Subaccount**: name `customer2`, region **US East (VA) us10**, subdomain e.g. `customer2-<your initials>`.
2. In `customer2`: **Instances and Subscriptions → Create → Kaizen**. A new, empty database is created for this customer automatically.
3. Route: `cf map-route kaizen-btp cfapps.us10-001.hana.ondemand.com --hostname <customer2 subdomain>-4143d2d3trial-dev-kaizen-btp`
4. In `customer2`: **Security → Users** → assign the **Kaizen …** role collections to yourself.
5. Open the customer 2 URL: no kaizens from customer 1 are visible. Create one; it does not appear for customer 1.

## 4. Optional: customer's S/4HANA

In the customer's subaccount: **Connectivity → Destinations → New**: name `S4HANA`, URL of their S/4 system, authentication as agreed
(for the SAP sandbox: URL `https://sandbox.api.sap.com/s4hanacloud`, NoAuthentication, additional property `URL.headers.APIKey = <key>`).
Then **Manage Kaizens → Sync machines from S/4HANA** (plant manager).

## If something goes wrong

| Symptom | Fix |
|---|---|
| `make: executable file not found` | step 0.1, then a new terminal |
| Deploy fails on memory quota | `cf apps`: stop unused apps; the trial has 4 GB |
| Subscription stays "Processing" / fails with a HANA or database error | HANA not running (step 0.2), or add a second instance mapping in HANA Cloud Central with only the org ID `adf6746d-9fb8-4f2a-bc3f-b10872efc4d3` (group empty) |
| URL says "404 Not Found: Requested route does not exist" | the `cf map-route` step for that customer |
| Login works but "403 Forbidden" / empty screens | role collections not assigned in **that** subaccount, or log out and back in |
| Look at logs | `cf logs kaizen-btp-srv --recent`, `cf logs kaizen-btp-mtx --recent` |

Remove everything again: `cf undeploy kaizen-btp --delete-services` (unsubscribe the customers first).
