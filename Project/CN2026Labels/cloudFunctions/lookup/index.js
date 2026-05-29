/**
 * Lookup Cloud Function (HTTP trigger).
 *
 * Returns the public IPs of every RUNNING VM that belongs to the gRPC
 * server Managed Instance Group.  The client picks one and connects.
 *
 * Required runtime env vars:
 *   PROJECT_ID    - GCP project id
 *   ZONE          - zone of the MIG (e.g. europe-west1-b)
 *   MIG_NAME      - name of the gRPC server MIG
 *   GRPC_PORT     - port the gRPC server listens on (default 8000)
 *
 * Deploy example (gen 2):
 *   gcloud functions deploy lookup \
 *     --gen2 --region=europe-west1 --runtime=nodejs20 \
 *     --source=. --entry-point=lookup --trigger-http --allow-unauthenticated \
 *     --set-env-vars=PROJECT_ID=$P,ZONE=$Z,MIG_NAME=grpc-server-mig,GRPC_PORT=8000
 */
const functions = require('@google-cloud/functions-framework');
const compute = require('@google-cloud/compute');

const PROJECT_ID = process.env.PROJECT_ID;
const ZONE       = process.env.ZONE;
const MIG_NAME   = process.env.MIG_NAME;
const GRPC_PORT  = parseInt(process.env.GRPC_PORT || '8000', 10);

const migClient      = new compute.InstanceGroupManagersClient();
const instanceClient = new compute.InstancesClient();

functions.http('lookup', async (req, res) => {
  try {
    // 1) ask the MIG manager for the list of managed instances
    const [members] = await migClient.listManagedInstances({
      project:              PROJECT_ID,
      zone:                 ZONE,
      instanceGroupManager: MIG_NAME,
    });

    // 2) fetch each instance to obtain its external IP
    const servers = [];
    for (const mi of members) {
      // mi.instance is a full URL: .../instances/<name>
      const name = mi.instance.split('/').pop();
      if (mi.instanceStatus !== 'RUNNING') continue;
      try {
        const [inst] = await instanceClient.get({
          project:  PROJECT_ID,
          zone:     ZONE,
          instance: name,
        });
        const nic = (inst.networkInterfaces || [])[0] || {};
        const access = (nic.accessConfigs || [])[0] || {};
        const ip = access.natIP;
        if (ip) servers.push({ name, ip, port: GRPC_PORT });
      } catch (e) {
        // Instance might have been deleted between list and get -> skip.
        console.warn('skip instance', name, e.message);
      }
    }

    res.set('Cache-Control', 'no-store');
    res.status(200).json({ servers });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});
