// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol;

/** The authentication-module island wire — extracted from
 *  {@link ProtocolEmitter} (file-size guardrail). */
final class AuthSpecEmitter {

    private AuthSpecEmitter() {
    }

    /** Recursive AWS credentials wire (probe t2-authentication):
     *  awsDefault spanless; awsStatic / awsSTSAssumeRole span the kind
     *  keyword through the closing brace. */
    private static void awsCredentials(StringBuilder b,
            Protocol.PAwsCredentials creds) {
        switch (creds) {
            case Protocol.PAwsDefault ignored ->
                    b.append("{\"_type\":\"awsDefault\"}");
            case Protocol.PAwsStatic st -> {
                b.append("{\"_type\":\"awsStatic\",\"accessKeyId\":");
                ProtocolEmitter.vaultSecret(b, st.accessKeyId());
                b.append(",\"secretAccessKey\":");
                ProtocolEmitter.vaultSecret(b, st.secretAccessKey());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, st.sourceInformation());
                b.append('}');
            }
            case Protocol.PAwsStsRole sts -> {
                b.append("{\"_type\":\"awsSTSAssumeRole\","
                        + "\"awsCredentials\":");
                awsCredentials(b, sts.awsCredentials());
                b.append(",\"roleArn\":");
                ProtocolEmitter.str(b, sts.roleArn());
                b.append(",\"roleSessionName\":");
                ProtocolEmitter.str(b, sts.roleSessionName());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, sts.sourceInformation());
                b.append('}');
            }
        }
    }

    /** The authentication-module island wire (probed auth-wire
     *  2026-08-14): userPassword / apiKey (location UPPERCASED) /
     *  kerberos (empty) / encryptedPrivateKey. */
    static void esAuthSpec(StringBuilder b,
            Protocol.PAuthSpecValue auth) {
        switch (auth) {
            case Protocol.PMongoAuth up -> {
                b.append("{\"_type\":\"userPassword\",\"password\":");
                ProtocolEmitter.vaultSecret(b, up.password());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, up.sourceInformation());
                b.append(",\"username\":");
                ProtocolEmitter.str(b, up.username());
                b.append('}');
            }
            case Protocol.PApiKeyAuth ak -> {
                b.append("{\"_type\":\"apiKey\",\"keyName\":");
                ProtocolEmitter.str(b, ak.keyName());
                b.append(",\"location\":");
                ProtocolEmitter.str(b, ak.location());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, ak.sourceInformation());
                b.append(",\"value\":");
                ProtocolEmitter.vaultSecret(b, ak.value());
                b.append('}');
            }
            case Protocol.PKerberosAuth k -> {
                b.append("{\"_type\":\"kerberos\","
                        + "\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, k.sourceInformation());
                b.append('}');
            }
            case Protocol.PPskAuth psk -> {
                b.append("{\"_type\":\"PSK\",\"psk\":");
                ProtocolEmitter.str(b, psk.psk());
                b.append('}');
            }
            case Protocol.PGcpWifIslandAuth g -> {
                b.append("{\"_type\":\"gcpWithAWSIdP\","
                        + "\"additionalGcpScopes\":[],"
                        + "\"idPConfiguration\":{\"accountId\":");
                ProtocolEmitter.str(b, g.accountId());
                b.append(",\"awsCredentials\":");
                awsCredentials(b, g.awsCredentials());
                b.append(",\"region\":");
                ProtocolEmitter.str(b, g.region());
                b.append(",\"role\":");
                ProtocolEmitter.str(b, g.role());
                b.append("},\"serviceAccountEmail\":");
                ProtocolEmitter.str(b, g.serviceAccountEmail());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, g.sourceInformation());
                b.append(",\"workloadConfiguration\":{\"poolId\":");
                ProtocolEmitter.str(b, g.poolId());
                b.append(",\"projectNumber\":");
                ProtocolEmitter.str(b, g.projectNumber());
                b.append(",\"providerId\":");
                ProtocolEmitter.str(b, g.providerId());
                b.append("}}");
            }
            case Protocol.PEpkAuth ek -> {
                b.append("{\"_type\":\"encryptedPrivateKey\","
                        + "\"passphrase\":");
                ProtocolEmitter.vaultSecret(b, ek.passphrase());
                b.append(",\"privateKey\":");
                ProtocolEmitter.vaultSecret(b, ek.privateKey());
                b.append(",\"sourceInformation\":");
                ProtocolEmitter.srcInfo(b, ek.sourceInformation());
                b.append(",\"userName\":");
                ProtocolEmitter.str(b, ek.userName());
                b.append('}');
            }
        }
    }
}
