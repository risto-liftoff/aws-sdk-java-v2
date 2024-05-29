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

package software.amazon.awssdk.services.s3.internal.multipart;

import static software.amazon.awssdk.services.s3.multipart.S3MultipartExecutionAttribute.MULTIPART_DOWNLOAD_RESUME_CONTEXT;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.SplittingTransformerConfiguration;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@SdkInternalApi
public class DownloadObjectHelper {
    private final S3AsyncClient s3AsyncClient;
    private final long bufferSizeInBytes;

    public DownloadObjectHelper(S3AsyncClient s3AsyncClient, long bufferSizeInBytes) {
        this.s3AsyncClient = s3AsyncClient;
        this.bufferSizeInBytes = bufferSizeInBytes;
    }

    public <T> CompletableFuture<T> downloadObject(
        GetObjectRequest getObjectRequest, AsyncResponseTransformer<GetObjectResponse, T> asyncResponseTransformer) {
        if (getObjectRequest.range() != null || getObjectRequest.partNumber() != null) {
            return s3AsyncClient.getObject(getObjectRequest, asyncResponseTransformer);
        }
        GetObjectRequest requestToPerform = getObjectRequest.toBuilder().checksumMode(ChecksumMode.ENABLED).build();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split =
            asyncResponseTransformer.split(SplittingTransformerConfiguration.builder()
                                                                            .bufferSizeInBytes(bufferSizeInBytes)
                                                                            .build());
        MultipartDownloaderSubscriber subscriber = subscriber(requestToPerform);
        split.publisher().subscribe(subscriber);
        return split.resultFuture();
    }

    private MultipartDownloaderSubscriber subscriber(GetObjectRequest getObjectRequest) {
        Optional<MultipartDownloadResumeContext> multipartDownloadContext = getObjectRequest
            .overrideConfiguration()
            .flatMap(conf -> Optional.ofNullable(conf.executionAttributes().getAttribute(MULTIPART_DOWNLOAD_RESUME_CONTEXT)));
        if (!multipartDownloadContext.isPresent()) {
            return new MultipartDownloaderSubscriber(s3AsyncClient, getObjectRequest);
        }
        int highestCompletedPart = multipartDownloadContext.map(MultipartDownloadResumeContext::highestSequentialCompletedPart)
                                                           .orElse(0);
        return new MultipartDownloaderSubscriber(s3AsyncClient, getObjectRequest, highestCompletedPart);
    }
}
