# CN2026Labels — Run book

Copy-paste commands, in order, from a **PowerShell** prompt opened **after**
`gcloud` and `mvn` have been installed and added to `PATH`.

All commands assume the working directory is:

```powershell
cd D:\ISEL\CN\CN_G06\Project\CN2026Labels\deploy
```

## 0. One-time setup (only the very first time on a machine)

```powershell
# allow signed local scripts (gcloud's wrappers)
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

# log in to GCP
gcloud auth login
gcloud config set project g6-cn-leirt
gcloud auth application-default login
```

## 1. Deploy everything to GCP

```powershell
.\00-bootstrap.ps1                # APIs, SA, buckets, Pub/Sub, Firestore, firewall
.\10-build-and-upload-jars.ps1    # mvn package + upload JARs to config bucket
.\20-create-templates-and-migs.ps1
.\30-deploy-functions.ps1         # prints the Lookup URL on stdout
```

Save the **Lookup URL** the last script prints (looks like
`https://lookup-7e5h4zcgvq-ew.a.run.app`) — the client needs it.

## 2. Wait for the VMs to be ready (~90–120 s)

```powershell
# check the gRPC server VM has finished booting and started the JVM
$grpcVm = gcloud compute instance-groups managed list-instances grpc-server-mig `
            --zone=europe-west1-b --format="value(NAME)"
gcloud compute ssh $grpcVm --zone=europe-west1-b `
  --command="sudo tail -5 /var/log/cn2026-startup.log"
```

Re-run until the last line is **`[gRPC] server started on port 8000`**. Then:

```powershell
$grpcIp = gcloud compute instances list --filter="name~grpc" `
            --format="value(EXTERNAL_IP)" | Select-Object -First 1
Test-NetConnection -ComputerName $grpcIp -Port 8000
# expect TcpTestSucceeded : True
```

## 3. Run the client

```powershell
. .\env.ps1
$LOOKUP_URL = gcloud functions describe $env:LOOKUP_FUNCTION `
                --region=$env:REGION --format='value(serviceConfig.uri)'
java -jar ..\client\target\cn2026-labels-client-1.0-jar-with-dependencies.jar $LOOKUP_URL
```

Suggested menu flow for the demo:

| Step | Menu option | What to type |
|------|-------------|--------------|
| Sanity check     | **7** | (nothing) — should show 1 gRPC + 1 worker |
| Submit an image  | **1** | `D:\ISEL\CN\CN_G06\Project\vision-translation\cat.jpg` — note the `requestId` |
| Wait ~10 s       |  –   | worker pulls from Pub/Sub, calls Vision + Translate, writes Firestore |
| Get result       | **2** | paste the `requestId` |
| Query by label   | **3** | `cat` (skip dates) |
| Download blob    | **4** | paste the `requestId` |
| Scale workers up | **6** | `3` — confirm with **7** |
| Scale back down  | **6** | `1` |
| Exit             | **9** |   |

## 4. Tear down (don't skip — stops billing)

```powershell
.\99-teardown.ps1
```

## 5. Re-running after a code change

If you change Java code:

```powershell
.\10-build-and-upload-jars.ps1            # rebuild + re-upload JARs

# force MIG VMs to recycle so they pull the new JAR on boot
$grpcVm   = gcloud compute instance-groups managed list-instances grpc-server-mig `
              --zone=europe-west1-b --format="value(NAME)"
$labelsVm = gcloud compute instance-groups managed list-instances labels-app-mig `
              --zone=europe-west1-b --format="value(NAME)"
gcloud compute instance-groups managed recreate-instances grpc-server-mig `
  --instances=$grpcVm   --zone=europe-west1-b
gcloud compute instance-groups managed recreate-instances labels-app-mig `
  --instances=$labelsVm --zone=europe-west1-b
```

Then step 2 (`tail` until `[gRPC] server started`) and step 3 (run the client)
again.

If you change the **startup script** or the **instance template metadata**, you
must delete and recreate the templates and MIGs:

```powershell
gcloud compute instance-groups managed delete grpc-server-mig --zone=europe-west1-b --quiet
gcloud compute instance-groups managed delete labels-app-mig  --zone=europe-west1-b --quiet
gcloud compute instance-templates delete grpc-server-tpl --quiet
gcloud compute instance-templates delete labels-app-tpl  --quiet
.\20-create-templates-and-migs.ps1
```
