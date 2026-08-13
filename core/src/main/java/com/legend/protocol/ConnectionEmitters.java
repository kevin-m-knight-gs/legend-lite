// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol;

import static com.legend.protocol.ProtocolEmitter.srcInfo;
import static com.legend.protocol.ProtocolEmitter.str;

/** Wire emission for a relational connection's THREE flavor switches —
 *  auth strategies, mapper post-processors and datasource specifications —
 *  split from {@link ProtocolEmitter} (guardrail: file size), same
 *  package-private seam. */
final class ConnectionEmitters {

    private ConnectionEmitters() {
    }

    static void authStrategy(StringBuilder b, Protocol.PAuthStrategy a) {
        switch (a) {
            case Protocol.PH2Default h -> {
                b.append("{\"_type\":\"h2Default\",\"sourceInformation\":");
                srcInfo(b, h.sourceInformation());
                b.append('}');
            }
            case Protocol.PTestAuth t -> {
                b.append("{\"_type\":\"test\",\"sourceInformation\":");
                srcInfo(b, t.sourceInformation());
                b.append('}');
            }
            case Protocol.PUserNamePassword u -> {
                b.append("{\"_type\":\"userNamePassword\"");
                if (u.baseVaultReference() != null) {
                    b.append(",\"baseVaultReference\":");
                    str(b, u.baseVaultReference());
                }
                b.append(",\"passwordVaultReference\":");
                str(b, u.passwordVaultReference());
                b.append(",\"sourceInformation\":");
                srcInfo(b, u.sourceInformation());
                b.append(",\"userNameVaultReference\":");
                str(b, u.userNameVaultReference());
                b.append('}');
            }
            case Protocol.POAuth o -> {
                b.append("{\"_type\":\"oauth\",\"oauthKey\":");
                str(b, o.oauthKey());
                b.append(",\"scopeName\":");
                str(b, o.scopeName());
                b.append(",\"sourceInformation\":");
                srcInfo(b, o.sourceInformation());
                b.append('}');
            }
            case Protocol.PDelegatedKerberos k -> {
                b.append("{\"_type\":\"delegatedKerberos\"");
                if (k.serverPrincipal() != null) {
                    b.append(",\"serverPrincipal\":");
                    str(b, k.serverPrincipal());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, k.sourceInformation());
                b.append('}');
            }
            case Protocol.PSnowflakePublic s -> {
                b.append("{\"_type\":\"snowflakePublic\","
                        + "\"passPhraseVaultReference\":");
                str(b, s.passPhraseVaultReference());
                b.append(",\"privateKeyVaultReference\":");
                str(b, s.privateKeyVaultReference());
                b.append(",\"publicUserName\":");
                str(b, s.publicUserName());
                b.append(",\"sourceInformation\":");
                srcInfo(b, s.sourceInformation());
                b.append('}');
            }
            case Protocol.PGCPApplicationDefaultCredentials g -> {
                b.append("{\"_type\":\"gcpApplicationDefaultCredentials\","
                        + "\"sourceInformation\":");
                srcInfo(b, g.sourceInformation());
                b.append('}');
            }
            case Protocol.PApiToken a2 -> {
                b.append("{\"_type\":\"apiToken\",\"apiToken\":");
                str(b, a2.apiToken());
                b.append(",\"sourceInformation\":");
                srcInfo(b, a2.sourceInformation());
                b.append('}');
            }
            case Protocol.PMiddleTierUserNamePassword m -> {
                b.append("{\"_type\":\"middleTierUserNamePassword\","
                        + "\"sourceInformation\":");
                srcInfo(b, m.sourceInformation());
                b.append(",\"vaultReference\":");
                str(b, m.vaultReference());
                b.append('}');
            }
            case Protocol.PGcpWifAuth w -> {
                b.append("{\"_type\":\"gcpWorkloadIdentityFederation\"");
                if (w.additionalGcpScopes() != null) {
                    b.append(",\"additionalGcpScopes\":[");
                    for (int i = 0; i < w.additionalGcpScopes().size(); i++) {
                        if (i > 0) {
                            b.append(',');
                        }
                        str(b, w.additionalGcpScopes().get(i));
                    }
                    b.append(']');
                }
                b.append(",\"serviceAccountEmail\":");
                str(b, w.serviceAccountEmail());
                b.append(",\"sourceInformation\":");
                srcInfo(b, w.sourceInformation());
                b.append('}');
            }
                                }
    }

    static void mapper(StringBuilder b, Protocol.PMapper m) {
        switch (m) {
            case Protocol.PTableMapper t -> {
                b.append("{\"_type\":\"table\",\"from\":");
                str(b, t.from());
                b.append(",\"schema\":{\"_type\":\"schema\",\"from\":");
                str(b, t.schemaFrom());
                b.append(",\"to\":");
                str(b, t.schemaTo());
                b.append("},\"to\":");
                str(b, t.to());
                b.append('}');
            }
            case Protocol.PSchemaMapper sm -> {
                b.append("{\"_type\":\"schema\",\"from\":");
                str(b, sm.from());
                b.append(",\"to\":");
                str(b, sm.to());
                b.append('}');
            }
        }
    }

    static void datasourceSpec(StringBuilder b, Protocol.PDatasourceSpec d) {
        switch (d) {
            case Protocol.PH2Local h -> {
                b.append("{\"_type\":\"h2Local\",\"sourceInformation\":");
                srcInfo(b, h.sourceInformation());
                if (h.testDataSetupCsv() != null) {
                    b.append(",\"testDataSetupCsv\":");
                    str(b, h.testDataSetupCsv());
                }
                if (h.testDataSetupSqls() != null) {
                    b.append(",\"testDataSetupSqls\":[");
                    for (int i = 0; i < h.testDataSetupSqls().size(); i++) {
                        if (i > 0) {
                            b.append(',');
                        }
                        str(b, h.testDataSetupSqls().get(i));
                    }
                    b.append(']');
                }
                b.append('}');
            }
            case Protocol.PDuckDBSpec dd -> {
                // probed wire (ZMigrationTargetProbe): _type, [path], si
                b.append("{\"_type\":\"duckDB\"");
                if (dd.path() != null) {
                    b.append(",\"path\":");
                    str(b, dd.path());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, dd.sourceInformation());
                b.append('}');
            }
            case Protocol.PSQLiteSpec sq ->
                    // a LITE backend with NO engine wire shape — refusing
                    // loudly beats inventing a _type the engine never reads
                    throw new IllegalStateException("SQLite datasource specs"
                            + " have no engine wire shape (lite backend)");
            case Protocol.PStaticSpec st -> {
                b.append("{\"_type\":\"static\",\"databaseName\":");
                str(b, st.databaseName());
                b.append(",\"host\":");
                str(b, st.host());
                b.append(",\"port\":").append(st.port());
                b.append(",\"sourceInformation\":");
                srcInfo(b, st.sourceInformation());
                b.append('}');
            }
            case Protocol.PSnowflakeSpec s -> {
                b.append("{\"_type\":\"snowflake\",\"accountName\":");
                str(b, s.accountName());
                if (s.accountType() != null) {
                    b.append(",\"accountType\":");
                    str(b, s.accountType());
                }
                if (s.cloudType() != null) {
                    b.append(",\"cloudType\":");
                    str(b, s.cloudType());
                }
                b.append(",\"databaseName\":");
                str(b, s.databaseName());
                if (s.enableQueryTags() != null) {
                    b.append(",\"enableQueryTags\":")
                            .append(s.enableQueryTags());
                }
                if (s.nonProxyHosts() != null) {
                    b.append(",\"nonProxyHosts\":");
                    str(b, s.nonProxyHosts());
                }
                if (s.organization() != null) {
                    b.append(",\"organization\":");
                    str(b, s.organization());
                }
                if (s.proxyHost() != null) {
                    b.append(",\"proxyHost\":");
                    str(b, s.proxyHost());
                }
                if (s.proxyPort() != null) {
                    b.append(",\"proxyPort\":");
                    str(b, s.proxyPort());
                }
                if (s.quotedIdentifiersIgnoreCase() != null) {
                    b.append(",\"quotedIdentifiersIgnoreCase\":")
                            .append(s.quotedIdentifiersIgnoreCase());
                }
                b.append(",\"region\":");
                str(b, s.region());
                if (s.role() != null) {
                    b.append(",\"role\":");
                    str(b, s.role());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, s.sourceInformation());
                if (s.tempTableDb() != null) {
                    b.append(",\"tempTableDb\":");
                    str(b, s.tempTableDb());
                }
                if (s.tempTableSchema() != null) {
                    b.append(",\"tempTableSchema\":");
                    str(b, s.tempTableSchema());
                }
                b.append(",\"warehouseName\":");
                str(b, s.warehouseName());
                b.append('}');
            }
            case Protocol.PSpannerSpec s -> {
                b.append("{\"_type\":\"spanner\",\"databaseId\":");
                str(b, s.databaseId());
                b.append(",\"instanceId\":");
                str(b, s.instanceId());
                b.append(",\"projectId\":");
                str(b, s.projectId());
                if (s.proxyHost() != null) {
                    b.append(",\"proxyHost\":");
                    str(b, s.proxyHost());
                }
                if (s.proxyPort() != null) {
                    b.append(",\"proxyPort\":").append(s.proxyPort());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, s.sourceInformation());
                b.append('}');
            }
            case Protocol.PDatabricksSpec s -> {
                b.append("{\"_type\":\"databricks\",\"hostname\":");
                str(b, s.hostname());
                b.append(",\"httpPath\":");
                str(b, s.httpPath());
                b.append(",\"port\":");
                str(b, s.port());
                b.append(",\"protocol\":");
                str(b, s.protocol());
                b.append(",\"sourceInformation\":");
                srcInfo(b, s.sourceInformation());
                b.append('}');
            }
            case Protocol.PBigQuerySpec s -> {
                b.append("{\"_type\":\"bigQuery\",\"defaultDataset\":");
                str(b, s.defaultDataset());
                b.append(",\"projectId\":");
                str(b, s.projectId());
                b.append(",\"sourceInformation\":");
                srcInfo(b, s.sourceInformation());
                b.append('}');
            }
                                }
    }
}
