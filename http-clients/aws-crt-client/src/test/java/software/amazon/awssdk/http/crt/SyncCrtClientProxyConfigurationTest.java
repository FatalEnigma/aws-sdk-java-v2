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

package software.amazon.awssdk.http.crt;

import java.io.IOException;
import software.amazon.awssdk.crt.http.HttpException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.proxy.HttpClientDefaultPoxyConfigTestSuite;

public class SyncCrtClientProxyConfigurationTest extends HttpClientDefaultPoxyConfigTestSuite {

    @Override
    protected Class<? extends Exception> getProxyFailedExceptionType() {
        return IOException.class;
    }

    @Override
    protected Class<? extends Exception> getProxyFailedCauseExceptionType() {
        return HttpException.class;
    }

    @Override
    protected boolean isSyncClient() {
        return true;
    }

    @Override
    protected SdkAsyncHttpClient createHttpClientWithDefaultProxy() {
        throw new UnsupportedOperationException("Async client does not support proxy");
    }

    @Override
    protected SdkHttpClient createSyncHttpClientWithDefaultProxy() {
        return AwsCrtHttpClient.create();
    }
}
