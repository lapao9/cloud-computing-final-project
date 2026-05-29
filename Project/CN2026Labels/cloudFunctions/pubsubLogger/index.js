/**
 * Optional Pub/Sub Cloud Function.
 *
 * Triggered every time the gRPC server publishes a task message.
 * Logs the request into Firestore (collection requests-log) so that
 * we have an audit trail independent from the worker MIG.
 *
 * Deploy example (gen 2):
 *   gcloud functions deploy pubsub-logger \
 *     --gen2 --region=europe-west1 --runtime=nodejs20 --source=. \
 *     --entry-point=logRequest \
 *     --trigger-topic=labels-tasks \
 *     --set-env-vars=PROJECT_ID=$P,COLLECTION=requests-log
 */
const functions = require('@google-cloud/functions-framework');
const { Firestore, FieldValue } = require('@google-cloud/firestore');

const db = new Firestore({ projectId: process.env.PROJECT_ID });
const COLLECTION = process.env.COLLECTION || 'requests-log';

functions.cloudEvent('logRequest', async (cloudEvent) => {
  try {
    const b64 = cloudEvent.data && cloudEvent.data.message && cloudEvent.data.message.data;
    if (!b64) {
      console.warn('No data in Pub/Sub message');
      return;
    }
    const json = Buffer.from(b64, 'base64').toString('utf8');
    const task = JSON.parse(json);
    await db.collection(COLLECTION).doc(task.requestId).set({
      requestId: task.requestId,
      bucket:    task.bucket,
      blob:      task.blob,
      filename:  task.filename,
      receivedAt: FieldValue.serverTimestamp(),
    });
    console.log('logged', task.requestId);
  } catch (e) {
    console.error('logger error', e);
    throw e;   // forces redelivery
  }
});
