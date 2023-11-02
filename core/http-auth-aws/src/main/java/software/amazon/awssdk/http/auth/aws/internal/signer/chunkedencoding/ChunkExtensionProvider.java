/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.auth.aws.internal.signer.chunkedencoding;

import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.utils.Pair;

/**
 * A functional interface for defining an extension of a chunk, where the extension is a key-value pair.
 * <p>
 * An extension usually depends on the chunk-data itself (checksum, signature, etc.), but is not required to. Per <a
 * href="https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.1">RFC-7230</a> The chunk-extension is defined as:
 * <pre>
 *     chunk-ext      = *( ";" chunk-ext-name [ "=" chunk-ext-val ] )
 *     chunk-ext-name = token
 *     chunk-ext-val  = token / quoted-string
 * </pre>
 */
@FunctionalInterface
@SdkInternalApi
public interface ChunkExtensionProvider extends Resettable {
    Pair<byte[], byte[]> get(byte[] chunk);
}
