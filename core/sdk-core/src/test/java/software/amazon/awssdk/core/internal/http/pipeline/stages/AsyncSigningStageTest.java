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

package software.amazon.awssdk.core.internal.http.pipeline.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.core.interceptor.SdkExecutionAttribute.TIME_OFFSET;
import static software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME;
import static software.amazon.awssdk.core.metrics.CoreMetric.SIGNING_DURATION;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivestreams.Publisher;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SelectedAuthScheme;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.http.ExecutionContext;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.internal.http.HttpClientDependencies;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.core.signer.AsyncRequestBodySigner;
import software.amazon.awssdk.core.signer.AsyncSigner;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.spi.scheme.AuthSchemeOption;
import software.amazon.awssdk.http.auth.spi.signer.AsyncSignRequest;
import software.amazon.awssdk.http.auth.spi.signer.AsyncSignedRequest;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignerProperty;
import software.amazon.awssdk.identity.spi.Identity;
import software.amazon.awssdk.metrics.MetricCollector;
import utils.ValidSdkObjects;

@RunWith(MockitoJUnitRunner.class)
public class AsyncSigningStageTest {
    private static final int TEST_TIME_OFFSET = 17;
    private static final SignerProperty<String> SIGNER_PROPERTY = SignerProperty.create(AsyncSigningStageTest.class, "key");

    @Mock
    private Identity identity;

    @Mock
    private HttpSigner<Identity> httpSigner;

    @Mock
    private Signer oldSigner;

    @Mock
    MetricCollector metricCollector;

    @Captor
    private ArgumentCaptor<SignRequest<? extends Identity>> signRequestCaptor;

    @Captor
    private ArgumentCaptor<AsyncSignRequest<? extends Identity>> asyncSignRequestCaptor;

    private HttpClientDependencies httpClientDependencies;
    private AsyncSigningStage stage;

    @Before
    public void setup() {
        httpClientDependencies = HttpClientDependencies.builder()
                                                       .clientConfiguration(SdkClientConfiguration.builder().build())
                                                       .build();
        // when tests update TimeOffset to non-zero value, it also sets SdkGlobalTime.setGlobalTimeOffset,
        // so explicitly setting this to default value before each test.
        httpClientDependencies.updateTimeOffset(0);
        stage = new AsyncSigningStage(httpClientDependencies);
    }

    @Test
    public void execute_selectedAuthScheme_nullSigner_doesSraSign() throws Exception {
        // Set up a scheme with a signer property
        SelectedAuthScheme<Identity> selectedAuthScheme = new SelectedAuthScheme<>(
            CompletableFuture.completedFuture(identity),
            httpSigner,
            AuthSchemeOption.builder()
                            .schemeId("my.auth#myAuth")
                            .putSignerProperty(SIGNER_PROPERTY, "value")
                            .build());
        RequestExecutionContext context = createContext(selectedAuthScheme, null);

        SdkHttpRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        when(httpSigner.sign(ArgumentMatchers.<SignRequest<? extends Identity>>any()))
            .thenReturn(SignedRequest.builder()
                                     .request(signedRequest)
                                     .build());

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();
        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that interceptor context is updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);

        // assert that the input to the signer is as expected, including that signer properties are set
        verify(httpSigner).sign(signRequestCaptor.capture());
        SignRequest<? extends Identity> signRequest = signRequestCaptor.getValue();
        assertThat(signRequest.identity()).isSameAs(identity);
        assertThat(signRequest.request()).isSameAs(request);
        assertThat(signRequest.property(SIGNER_PROPERTY)).isEqualTo("value");

        // Assert that the time offset set was zero
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK)).isNotNull();
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK).instant())
            .isCloseTo(Instant.now(), within(10, ChronoUnit.MILLIS));

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(oldSigner);
    }

    @Test
    public void execute_selectedAuthScheme_nullSigner_timeOffsetSet_doesSraSignAndAdjustTheSigningClock() throws Exception {
        AsyncRequestBody asyncPayload = AsyncRequestBody.fromString("async request body");
        // Set up a scheme with a signer property
        SelectedAuthScheme<Identity> selectedAuthScheme = new SelectedAuthScheme<>(
            CompletableFuture.completedFuture(identity),
            httpSigner,
            AuthSchemeOption.builder()
                            .schemeId("my.auth#myAuth")
                            .putSignerProperty(SIGNER_PROPERTY, "value")
                            .build());
        RequestExecutionContext context = createContext(selectedAuthScheme, asyncPayload, null);

        SdkHttpRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        Publisher<ByteBuffer> signedPayload = AsyncRequestBody.fromString("signed async request body");
        when(httpSigner.signAsync(ArgumentMatchers.<AsyncSignRequest<? extends Identity>>any()))
            .thenReturn(
                CompletableFuture.completedFuture(AsyncSignedRequest.builder()
                                                                    .request(signedRequest)
                                                                    .payload(signedPayload)
                                                                    .build()));

        // Setup the timeoffset to test that the clock is setup properly.
        httpClientDependencies.updateTimeOffset(TEST_TIME_OFFSET);
        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();
        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that interceptor context is updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);

        // assert that the input to the signer is as expected, including that signer properties are set
        verify(httpSigner).signAsync(asyncSignRequestCaptor.capture());
        AsyncSignRequest<? extends Identity> signRequest = asyncSignRequestCaptor.getValue();
        assertThat(signRequest.identity()).isSameAs(identity);
        assertThat(signRequest.request()).isSameAs(request);
        assertThat(signRequest.property(SIGNER_PROPERTY)).isEqualTo("value");

        // Assert that the signing clock is setup properly
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK)).isNotNull();
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK).instant())
            .isCloseTo(Instant.now().minusSeconds(17)
                , within(10, ChronoUnit.MILLIS));

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(oldSigner);
    }

    @Test
    public void execute_selectedAuthScheme_nullSigner_doesSraSignAndDoesNotOverrideAuthSchemeOptionClock() throws Exception {
        AsyncRequestBody asyncPayload = AsyncRequestBody.fromString("async request body");
        // Set up a scheme with a signer property and the signing clock set
        Clock clock = SigningStageTest.testClock();
        SelectedAuthScheme<Identity> selectedAuthScheme = new SelectedAuthScheme<>(
            CompletableFuture.completedFuture(identity),
            httpSigner,
            AuthSchemeOption.builder()
                            .schemeId("my.auth#myAuth")
                            .putSignerProperty(SIGNER_PROPERTY, "value")
                            // The auth scheme option includes the signing clock property
                            .putSignerProperty(HttpSigner.SIGNING_CLOCK, clock)
                            .build());

        RequestExecutionContext context = createContext(selectedAuthScheme, asyncPayload, null);

        SdkHttpRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        when(httpSigner.signAsync(ArgumentMatchers.<AsyncSignRequest<? extends Identity>>any()))
            .thenReturn(
                CompletableFuture.completedFuture(AsyncSignedRequest.builder()
                                         .request(signedRequest)
                                         .build()));

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();
        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that interceptor context is updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);

        // assert that the input to the signer is as expected, including that signer properties are set
        verify(httpSigner).signAsync(asyncSignRequestCaptor.capture());
        AsyncSignRequest<? extends Identity> signRequest = asyncSignRequestCaptor.getValue();
        assertThat(signRequest.identity()).isSameAs(identity);
        assertThat(signRequest.request()).isSameAs(request);
        assertThat(signRequest.property(SIGNER_PROPERTY)).isEqualTo("value");

        // Assert that the time offset set was zero
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK)).isNotNull();
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK).instant())
            .isCloseTo(Instant.now(), within(10, ChronoUnit.MILLIS));

        // assert that the signing stage does not override the auth-option provided clock.
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK)).isSameAs(clock);

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(oldSigner);
    }

    @Test
    public void execute_selectedAuthScheme_asyncRequestBody_doesSraSignAsync() throws Exception {
        AsyncRequestBody asyncPayload = AsyncRequestBody.fromString("async request body");

        // Set up a scheme with a signer property
        SelectedAuthScheme<Identity> selectedAuthScheme = new SelectedAuthScheme<>(
            CompletableFuture.completedFuture(identity),
            httpSigner,
            AuthSchemeOption.builder()
                            .schemeId("my.auth#myAuth")
                            .putSignerProperty(SIGNER_PROPERTY, "value")
                            .build());
        RequestExecutionContext context = createContext(selectedAuthScheme, asyncPayload, null);

        SdkHttpRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        Publisher<ByteBuffer> signedPayload = AsyncRequestBody.fromString("signed async request body");
        when(httpSigner.signAsync(ArgumentMatchers.<AsyncSignRequest<? extends Identity>>any()))
            .thenReturn(
                CompletableFuture.completedFuture(AsyncSignedRequest.builder()
                                          .request(signedRequest)
                                          .payload(signedPayload)
                                          .build()));

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();
        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that contexts are updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);
        assertThat(context.executionContext().interceptorContext().asyncRequestBody().get()).isSameAs(signedPayload);
        assertThat(context.requestProvider()).isSameAs(signedPayload);

        // assert that the input to the signer is as expected, including that signer properties are set
        verify(httpSigner).signAsync(asyncSignRequestCaptor.capture());
        AsyncSignRequest<? extends Identity> signRequest = asyncSignRequestCaptor.getValue();
        assertThat(signRequest.identity()).isSameAs(identity);
        assertThat(signRequest.request()).isSameAs(request);
        assertThat(signRequest.payload().get()).isSameAs(asyncPayload);
        assertThat(signRequest.property(SIGNER_PROPERTY)).isEqualTo("value");

        // Assert that the time offset set was zero
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK)).isNotNull();
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK).instant())
            .isCloseTo(Instant.now(), within(10, ChronoUnit.MILLIS));

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(oldSigner);
    }

    @Test
    public void execute_selectedNoAuthAuthScheme_nullSigner_doesSraSign() throws Exception {
        AsyncRequestBody asyncPayload = AsyncRequestBody.fromString("async request body");
        // Set up a scheme with smithy.api#noAuth
        SelectedAuthScheme<Identity> selectedAuthScheme = new SelectedAuthScheme<>(
            CompletableFuture.completedFuture(identity),
            httpSigner,
            AuthSchemeOption.builder()
                            .schemeId("smithy.api#noAuth")
                            .putSignerProperty(SIGNER_PROPERTY, "value")
                            .build());
        RequestExecutionContext context = createContext(selectedAuthScheme, asyncPayload, null);

        SdkHttpRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        Publisher<ByteBuffer> signedPayload = AsyncRequestBody.fromString("signed async request body");
        when(httpSigner.signAsync(ArgumentMatchers.<AsyncSignRequest<? extends Identity>>any()))
            .thenReturn(
                CompletableFuture.completedFuture(AsyncSignedRequest.builder()
                                                                    .request(signedRequest)
                                                                    .payload(signedPayload)
                                                                    .build()));

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();
        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that contexts are updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);
        assertThat(context.executionContext().interceptorContext().asyncRequestBody().get()).isSameAs(signedPayload);
        assertThat(context.requestProvider()).isSameAs(signedPayload);

        // assert that the input to the signer is as expected, including that signer properties are set
        verify(httpSigner).signAsync(asyncSignRequestCaptor.capture());
        AsyncSignRequest<? extends Identity> signRequest = asyncSignRequestCaptor.getValue();
        assertThat(signRequest.identity()).isSameAs(identity);
        assertThat(signRequest.request()).isSameAs(request);
        assertThat(signRequest.property(SIGNER_PROPERTY)).isEqualTo("value");
        assertThat(signRequest.payload().get()).isSameAs(asyncPayload);

        // Assert that the time offset set was zero
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK)).isNotNull();
        assertThat(signRequest.property(HttpSigner.SIGNING_CLOCK).instant())
            .isCloseTo(Instant.now(), within(10, ChronoUnit.MILLIS));

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(oldSigner);
    }

    @Test
    public void execute_nullSelectedAuthScheme_signer_doesPreSraSign() throws Exception {
        RequestExecutionContext context = createContext(null, oldSigner);

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();

        SdkHttpFullRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        // Creating a copy because original executionAttributes may be directly mutated by SigningStage, e.g., timeOffset
        when(oldSigner.sign(request, context.executionAttributes().copy().putAttribute(TIME_OFFSET, 0))).thenReturn(signedRequest);

        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that interceptor context is updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(httpSigner);
    }

    @Test
    public void execute_nullSelectedAuthScheme_AsyncRequestBodySigner_doesPreSraSignAsyncRequestBody() throws Exception {
        AsyncRequestBody asyncPayload = AsyncRequestBody.fromString("async request body");
        AsyncRequestBody signedPayload = AsyncRequestBody.fromString("signed async request body");

        TestAsyncRequestBodySigner asyncRequestBodySigner = mock(TestAsyncRequestBodySigner.class);
        RequestExecutionContext context = createContext(null, asyncPayload, asyncRequestBodySigner);

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();

        SdkHttpFullRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        // Creating a copy because original executionAttributes may be directly mutated by SigningStage, e.g., timeOffset
        when(asyncRequestBodySigner.sign(request, context.executionAttributes().copy().putAttribute(TIME_OFFSET, 0))).thenReturn(signedRequest);
        when(asyncRequestBodySigner.signAsyncRequestBody(signedRequest,
                                                         asyncPayload,
                                                         context.executionAttributes().copy().putAttribute(TIME_OFFSET, 0)))
            .thenReturn(signedPayload);

        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that contexts are updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);
        // Note: pre SRA code did not set this, so commenting it out, as compared to the SRA test.
        // assertThat(context.executionContext().interceptorContext().asyncRequestBody().get()).isSameAs(signedPayload);
        assertThat(context.requestProvider()).isSameAs(signedPayload);

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(httpSigner);
    }

    @Test
    public void execute_nullSelectedAuthScheme_AsyncSigner_doesPreSraSignAsync() throws Exception {
        AsyncRequestBody asyncPayload = AsyncRequestBody.fromString("async request body");

        TestAsyncSigner asyncSigner = mock(TestAsyncSigner.class);
        RequestExecutionContext context = createContext(null, asyncPayload, asyncSigner);

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();

        SdkHttpFullRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        // Creating a copy because original executionAttributes may be directly mutated by SigningStage, e.g., timeOffset
        when(asyncSigner.sign(request,
                              asyncPayload,
                              context.executionAttributes().copy().putAttribute(TIME_OFFSET, 0)))
            .thenReturn(CompletableFuture.completedFuture(signedRequest));

        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that contexts are updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);
        // Note: compared to SRA code, AsyncSigner use case did not set asyncPayload on the context or the
        // interceptorContext.

        // So commenting the interceptorContext assertion, as compared to the SRA test.
        // assertThat(context.executionContext().interceptorContext().asyncRequestBody().get()).isSameAs(asyncPayload);

        // And the context.requestProvider is the input asyncPayload itself, there is no different signedPayload as compared to
        // the SRA test.
        assertThat(context.requestProvider()).isSameAs(asyncPayload);

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(httpSigner);
    }

    @Test
    public void execute_nullSelectedAuthScheme_nullSigner_skipsSigning() throws Exception {
        RequestExecutionContext context = createContext(null, null);

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();
        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(request);
        // assert that interceptor context is updated with result, which is same as request.
        // To ensure this asserts the logic in the SigningStage to update the InterceptorContext before the signing logic,
        // the request is not set in the InterceptorContext in createContext()
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(request);

        verifyNoInteractions(metricCollector);

        verifyNoInteractions(httpSigner);
    }

    @Test
    public void execute_nullSelectedAuthScheme_signer_usesTimeOffset() throws Exception {
        httpClientDependencies.updateTimeOffset(100);

        RequestExecutionContext context = createContext(null, oldSigner);

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();

        SdkHttpFullRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        // Creating a copy because original executionAttributes may be directly mutated by SigningStage, e.g., timeOffset
        when(oldSigner.sign(request, context.executionAttributes().copy().putAttribute(TIME_OFFSET, 100))).thenReturn(signedRequest);

        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that interceptor context is updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(httpSigner);
    }

    @Test
    public void execute_selectedAuthScheme_signer_doesPreSraSign() throws Exception {
        // Set up a scheme
        SelectedAuthScheme<Identity> selectedAuthScheme = new SelectedAuthScheme<>(
            CompletableFuture.completedFuture(identity),
            httpSigner,
            AuthSchemeOption.builder().schemeId("my.auth#myAuth").build());

        RequestExecutionContext context = createContext(selectedAuthScheme, oldSigner);

        SdkHttpFullRequest request = ValidSdkObjects.sdkHttpFullRequest().build();

        SdkHttpFullRequest signedRequest = ValidSdkObjects.sdkHttpFullRequest().build();
        // Creating a copy because original executionAttributes may be directly mutated by SigningStage, e.g., timeOffset
        when(oldSigner.sign(request, context.executionAttributes().copy().putAttribute(TIME_OFFSET, 0))).thenReturn(signedRequest);

        SdkHttpFullRequest result = stage.execute(request, context).join();

        assertThat(result).isSameAs(signedRequest);
        // assert that interceptor context is updated with result
        assertThat(context.executionContext().interceptorContext().httpRequest()).isSameAs(result);

        // assert that metrics are collected
        verify(metricCollector).reportMetric(eq(SIGNING_DURATION), any());

        verifyNoInteractions(httpSigner);
    }

    private RequestExecutionContext createContext(SelectedAuthScheme<Identity> selectedAuthScheme, Signer oldSigner) {
        return createContext(selectedAuthScheme, null, oldSigner);
    }

    private RequestExecutionContext createContext(SelectedAuthScheme<Identity> selectedAuthScheme,
                                                  AsyncRequestBody requestProvider,
                                                  Signer oldSigner) {
        SdkRequest sdkRequest = ValidSdkObjects.sdkRequest();
        InterceptorContext interceptorContext =
            InterceptorContext.builder()
                              .request(sdkRequest)
                              // Normally, this would be set, but there is logic to update the InterceptorContext before and
                              // after signing, so keeping it not set here, so that logic can be asserted in tests.
                              // .httpRequest(request)
                              .build();

        ExecutionAttributes executionAttributes = ExecutionAttributes.builder()
                                                                     .put(SELECTED_AUTH_SCHEME, selectedAuthScheme)
                                                                     .build();

        ExecutionContext executionContext = ExecutionContext.builder()
                                                            .executionAttributes(executionAttributes)
                                                            .interceptorContext(interceptorContext)
                                                            .signer(oldSigner)
                                                            .build();

        RequestExecutionContext context = RequestExecutionContext.builder()
                                                                 .executionContext(executionContext)
                                                                 .originalRequest(sdkRequest)
                                                                 .requestProvider(requestProvider)
                                                                 .build();
        context.attemptMetricCollector(metricCollector);
        return context;
    }

    private interface TestAsyncRequestBodySigner extends Signer, AsyncRequestBodySigner {
    }

    private interface TestAsyncSigner extends Signer, AsyncSigner {
    }
}
