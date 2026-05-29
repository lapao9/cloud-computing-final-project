# CN2026Labels — Changes Required to Run

Snapshot of every file edited from the initial commit to the first working deploy on
GCP project `g6-cn-leirt`. Each section explains *what* was changed and *why* so
the report can reference it.

## 1. Real project ID

**File:** [`deploy/env.ps1`](deploy/env.ps1)

```diff
- $env:PROJECT_ID = "CN2526-T3-G06"
+ $env:PROJECT_ID = "g6-cn-leirt"
```

`CN2526-T3-G06` is the project's *display name* in the GCP console; the actual
project **ID** (which gcloud needs everywhere) is `g6-cn-leirt`. The service
account email derives from `${PROJECT_ID}`, so this single change also fixes
`cn2026-runtime@g6-cn-leirt.iam.gserviceaccount.com`.

## 2. PowerShell 5.1 — stop halting on harmless 404s

**Files:** all four PS deploy scripts
- [`deploy/00-bootstrap.ps1`](deploy/00-bootstrap.ps1)
- [`deploy/10-build-and-upload-jars.ps1`](deploy/10-build-and-upload-jars.ps1)
- [`deploy/20-create-templates-and-migs.ps1`](deploy/20-create-templates-and-migs.ps1)
- [`deploy/30-deploy-functions.ps1`](deploy/30-deploy-functions.ps1)

```diff
- $ErrorActionPreference = "Stop"
+ $ErrorActionPreference = "Continue"
```

The scripts probe for resources with patterns like
`$x = gcloud ... describe ... 2>$null; if (-not $x) { create }`. On
**Windows PowerShell 5.1**, stderr lines from native processes invoked inside a
`.ps1` (gcloud is itself a `.ps1` wrapper) get wrapped as `ErrorRecord` objects.
Combined with `$ErrorActionPreference = "Stop"` that turns each *expected* 404
into a terminating error and aborts bootstrap before it ever creates the missing
resource. Switching to `Continue` lets the existence-then-create pattern actually
work.

## 3. Maven fat-JAR assembly — fix the gRPC NameResolver registry

**Files:**
- [`grpcServer/pom.xml`](grpcServer/pom.xml)
- [`labelsApp/pom.xml`](labelsApp/pom.xml)
- [`client/pom.xml`](client/pom.xml)

Replaced `maven-assembly-plugin` (`jar-with-dependencies` descriptor) with
**`maven-shade-plugin` + `ServicesResourceTransformer`**.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <shadedArtifactAttached>true</shadedArtifactAttached>
                <shadedClassifierName>jar-with-dependencies</shadedClassifierName>
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>cn2026.server.GrpcServerMain</mainClass>
                    </transformer>
                </transformers>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Why it mattered

The default `jar-with-dependencies` descriptor **overwrites** files when two
dependencies ship a resource at the same path. gRPC ships a SPI registry file
at `META-INF/services/io.grpc.NameResolverProvider` in *every* gRPC sub-module
(`grpc-netty-shaded`, `grpc-core`, `grpc-googleapis`, …). With the assembly
plugin, only the last-written file survives — in our case the one listing only
the `unix` (Unix-domain-socket) resolver. At runtime, gRPC saw only `unix`
registered, picked it for `firestore.googleapis.com:443`, and crashed with:

```
java.lang.IllegalArgumentException: Address types of NameResolver 'unix'
for 'firestore.googleapis.com:443' not supported by transport
```

`ServicesResourceTransformer` **merges** all `META-INF/services` files so every
gRPC sub-module's resolvers (`dns`, `unix`, `xds`, …) are all registered. After
the switch, gRPC picks `dns` for a normal host:port target — Firestore client
initializes cleanly.

The kept-output classifier `jar-with-dependencies` preserves the
`cn2026-labels-*-1.0-jar-with-dependencies.jar` filename the deploy scripts and
VM startup scripts already expect, so nothing else needed touching.

## 4. Dependency alignment — Google Cloud libraries BOM

**File:** [`pom.xml`](pom.xml) (parent)

```diff
- <protoc.version>3.25.5</protoc.version>
+ <protoc.version>4.28.3</protoc.version>
...
+ <dependencyManagement>
+     <dependencies>
+         <dependency>
+             <groupId>com.google.cloud</groupId>
+             <artifactId>libraries-bom</artifactId>
+             <version>26.65.0</version>
+             <type>pom</type>
+             <scope>import</scope>
+         </dependency>
+     </dependencies>
+ </dependencyManagement>
```

Removed explicit `<version>` tags from all `google-cloud-*` and `io.grpc:*`
runtime dependencies in:
- [`grpcContract/pom.xml`](grpcContract/pom.xml)
- [`grpcServer/pom.xml`](grpcServer/pom.xml)
- [`labelsApp/pom.xml`](labelsApp/pom.xml)

### Why it mattered

After fixing the NameResolver problem, the next crash was:

```
NoClassDefFoundError: com.google.protobuf.RuntimeVersion$RuntimeDomain
```

then

```
NoClassDefFoundError: com.google.api.gax.rpc.ResourceNameExtractor
```

Both are **version-mismatch** symptoms:

- `RuntimeVersion$RuntimeDomain` was added in **protobuf-java 4.27+**.
  `google-cloud-pubsub:1.150.1` (late-2025 release) was compiled against
  protobuf 4.x, but Maven's "nearest-version-wins" resolution was picking
  protobuf 3.25.5 from `protoc.version`. The compiled-against API was simply
  not present at runtime.
- `ResourceNameExtractor` is a more recent **`gax`** class. Pubsub 1.150.1
  needed a newer gax than what the rest of the dep tree was dragging in.

Importing the **Google Cloud Libraries BOM** pins every GCP service, `gax`,
`grpc-java`, and `protobuf-java` to a single internally-consistent
release train. Removing the per-module `<version>` tags lets the BOM be the
single source of truth — no more version drift between modules.

The BOM also conveniently pulls newer `pubsub` and `firestore` clients,
all of which still satisfy the project's functional requirements.

`protoc.version` is bumped to **4.28.3** because the `protobuf-maven-plugin`
needs a `protoc` binary, and the runtime now expects code generated by the
protobuf-4 generator. (gRPC plugin `1.70.0` works fine with protoc 4.x.)

## 5. Defensive runtime flags on the VMs (kept though not strictly necessary)

**Files:** [`deploy/grpc-server-startup.sh`](deploy/grpc-server-startup.sh),
[`deploy/labels-app-startup.sh`](deploy/labels-app-startup.sh)

```diff
- exec java -jar grpc-server.jar
+ export GOOGLE_CLOUD_DISABLE_DIRECT_PATH=true
+ exec java \
+   -Dcom.google.cloud.firestore.disableDirectPath=true \
+   -Dio.grpc.NameResolverProvider.enableServiceConfig=false \
+   -jar grpc-server.jar
```

Originally added while we were still hunting the NameResolver problem (I was
suspecting Google's "DirectPath" optimization, which auto-selects a Unix-domain
socket when the client runs inside Google's network). The real fix turned out
to be the shade plugin (§3), but these flags are a safe belt-and-braces
defense: disabling DirectPath forces the client to use the public DNS endpoint
even on GCE, which means any future shading regression won't silently route
back through `unix`-only paths.

## Operational recovery notes

A handful of resources from earlier exploratory deploy runs were left dangling
and had to be cleaned manually:

- **Stale instance templates** carried obsolete metadata (`cn-config-bucket =
  final_project_2526g6` and `cn-topic = final_project`). Because PowerShell
  swallowed the `instance-templates delete --quiet 2>$null` errors silently
  during a normal re-run, the script tried to `create` over already-existing
  templates, failed, and the new MIGs ended up pointing at the stale templates.
- **MIGs in another zone** (`europe-west4-a`) from a previous deployment with
  different region settings were still pinning the templates and blocking
  template deletion. Listing every MIG with
  `gcloud compute instance-groups managed list` (no zone filter) made them
  visible, and `delete --zone=europe-west4-a` for both released the templates.

For future re-deploys the script-driven `delete-then-create` flow is reliable
**as long as** there are no orphaned MIGs in other zones referencing the
templates. The `99-teardown.ps1` script removes everything in the configured
zone, so as long as the env file is consistent across runs this won't recur.

## Operational sequence that finally worked

```powershell
cd D:\ISEL\CN\CN_G06\Project\CN2026Labels\deploy
.\00-bootstrap.ps1               # APIs, SA, buckets, Pub/Sub, Firestore, firewall
.\10-build-and-upload-jars.ps1   # mvn package (shade) + upload to gs://...-config/jars/
.\20-create-templates-and-migs.ps1
.\30-deploy-functions.ps1        # prints Lookup URL — save it

# verify
$grpcVm = gcloud compute instance-groups managed list-instances grpc-server-mig --zone=europe-west1-b --format="value(NAME)"
gcloud compute ssh $grpcVm --zone=europe-west1-b --command="sudo tail -5 /var/log/cn2026-startup.log"
# expect "[gRPC] server started on port 8000"

# run the client
. .\env.ps1
java -jar ..\client\target\cn2026-labels-client-1.0-jar-with-dependencies.jar <LOOKUP_URL>
```

## TL;DR for the report

Three substantive engineering decisions:

1. **Use `maven-shade-plugin`, not `maven-assembly-plugin`, when packaging
   gRPC clients into a fat JAR.** The SPI-services file must be *merged*
   across all gRPC sub-modules or the runtime ends up with only one
   `NameResolver` registered.
2. **Manage GCP / gRPC / protobuf versions through the
   `com.google.cloud:libraries-bom`.** GCP releases monthly co-validated sets
   of all client libraries; mixing arbitrary versions is the fast path to
   `NoClassDefFoundError`s.
3. **Build for the actual environment.** The Compute Engine VMs run a minimal
   Debian image with no JRE pre-installed; the startup script must `apt-get
   install openjdk-17-jre-headless`, fetch the fat JAR from the config
   bucket, and run it under a service account that has Storage, Pub/Sub,
   Firestore, Translate, Vision, and Compute IAM roles.
