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

package software.amazon.awssdk.s3benchmarks;

import static software.amazon.awssdk.s3benchmarks.BenchmarkUtils.printOutResult;
import static software.amazon.awssdk.transfer.s3.SizeConstant.MB;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.async.SimplePublisher;

public class TransferManagerUploadBenchmark extends BaseTransferManagerBenchmark {
    private static final Logger logger = Logger.loggerFor("TransferManagerUploadBenchmark");
    private final TransferManagerBenchmarkConfig config;

    public TransferManagerUploadBenchmark(TransferManagerBenchmarkConfig config) {
        super(config);
        Validate.notNull(config.key(), "Key must not be null");
        Validate.mutuallyExclusive("Only one of --file or --contentLengthInMB option must be specified, but both were.",
                                   config.filePath(), config.contentLengthInMb());

        if (config.filePath() == null && config.contentLengthInMb() == null) {
            throw new IllegalArgumentException("Either --file or --contentLengthInMB must be specified, but none were.");
        }
        this.config = config;
    }

    @Override
    protected void doRunBenchmark() {
        try {
            doUplaod(iteration, true);
        } catch (Exception exception) {
            logger.error(() -> "Request failed: ", exception);
        }
    }

    @Override
    protected void additionalWarmup() {
        try {
            doUplaod(3, false);
        } catch (Exception exception) {
            logger.error(() -> "Warmup failed: ", exception);
        }
    }

    private void doUplaod(int count, boolean printOutResult) throws IOException {
        List<Double> metrics = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (config.contentLengthInMb() == null) {
                logger.info(() -> "Starting to upload from file");
                uploadOnceFromFile(metrics);
            } else {
                logger.info(() -> "Starting to upload from memory");
                uploadOnceFromMemory(metrics);
            }
        }
        if (printOutResult) {
            printOutResult(metrics, "Upload from File", Files.size(Paths.get(path)));
        }
    }

    private void uploadOnceFromFile(List<Double> latencies) {
        File sourceFile = new File(path);
        long start = System.currentTimeMillis();
        transferManager.uploadFile(b -> b.putObjectRequest(r -> r.bucket(bucket)
                                                                 .key(key)
                                                                 .checksumAlgorithm(config.checksumAlgorithm()))
                                         .source(sourceFile.toPath()))
                       .completionFuture().join();
        long end = System.currentTimeMillis();
        latencies.add((end - start) / 1000.0);
    }

    private void uploadOnceFromMemory(List<Double> latencies) {
        SimplePublisher<ByteBuffer> simplePublisher = new SimplePublisher<>();
        Long partSizeInMb = config.partSizeInMb() * MB;
        byte[] bytes = ByteBuffer.allocate(partSizeInMb.intValue()).array();
        UploadRequest uploadRequest = UploadRequest
            .builder()
            .putObjectRequest(r -> r.bucket(bucket)
                                    .key(key)
                                    .checksumAlgorithm(config.checksumAlgorithm()))
            .requestBody(AsyncRequestBody.fromPublisher(simplePublisher))
            .addTransferListener(LoggingTransferListener.create())
            .build();
        Executors.defaultThreadFactory().newThread(() -> {
            long remaining = config.contentLengthInMb() * MB;
            while (remaining > 0) {
                simplePublisher.send(ByteBuffer.wrap(bytes));
                remaining -= partSizeInMb;
                long r = remaining;
                logger.info(() -> "sending '" + partSizeInMb + "' bytes out of '" + r + "' remaining.");
            }
            simplePublisher.complete();
        }).start();
        long start = System.currentTimeMillis();
        transferManager.upload(uploadRequest).completionFuture().join();
        long end = System.currentTimeMillis();
        latencies.add((end - start) / 1000.0);
    }
}
