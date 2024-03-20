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

import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.core.internal.http.pipeline.RequestToRequestPipeline;
import software.amazon.awssdk.core.internal.progress.listener.ProgressUpdater;
import software.amazon.awssdk.http.SdkHttpFullRequest;

@SdkInternalApi
public class PreExecuteProgressUpdateStage implements RequestToRequestPipeline {
    @Override
    public SdkHttpFullRequest execute(SdkHttpFullRequest input, RequestExecutionContext context) throws Exception {

        if (progressListenerAttached(context.originalRequest())) {
            Long requestContentLength =
                context.requestProvider() != null && context.requestProvider().contentLength().isPresent() ?
                context.requestProvider().contentLength().get() : null;
            ProgressUpdater progressUpdater = new ProgressUpdater(context.originalRequest(), requestContentLength);
            progressUpdater.requestPrepared();
            progressUpdater.requestHeaderSent();

            // I think the right way is to set the EXECUTION Attribute or something better we can modify the Context here to
            // add updater.

            context.executionContext().toBuilder().progressUpdater(progressUpdater);
        }

        return input;
    }

    public boolean progressListenerAttached(SdkRequest request) {
        if (request.overrideConfiguration().isPresent() &&
            !request.overrideConfiguration().get().progressListeners().isEmpty()) {
            return true;
        }

        return false;
    }
}