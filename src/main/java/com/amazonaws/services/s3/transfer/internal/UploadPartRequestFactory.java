/*
 * Copyright 2010-2013 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.amazonaws.services.s3.transfer.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.services.s3.internal.InputSubstream;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.UploadPartRequest;

/**
 * Factory for creating all the individual UploadPartRequest objects for a
 * multipart upload.
 * <p>
 * This allows us to delay creating each UploadPartRequest until we're ready for
 * it, instead of immediately creating thousands of UploadPartRequest objects
 * for each large upload, when we won't need most of those request objects for a
 * while.
 */
public class UploadPartRequestFactory {
    private final String bucketName;
    private final String key;
    private final String uploadId;
    private final long optimalPartSize;
    private final File file;
    private final PutObjectRequest putObjectRequest;
    private int partNumber = 1;
    private long offset = 0;
    private long remainingBytes;
    private List<PartETag> alreadyUploadedParts;
    private long previouslyUpdatedBytes = 0;
    private List<Long> previouslyUpdatedBytesToSend = new ArrayList<Long>();

    public UploadPartRequestFactory(PutObjectRequest putObjectRequest, String uploadId, long optimalPartSize) {
        this.putObjectRequest = putObjectRequest;
        this.uploadId = uploadId;
        this.optimalPartSize = optimalPartSize;
        this.bucketName = putObjectRequest.getBucketName();
        this.key = putObjectRequest.getKey();
        this.file = TransferManagerUtils.getRequestFile(putObjectRequest);
        this.remainingBytes = TransferManagerUtils.getContentLength(putObjectRequest);
    }
    
    public UploadPartRequestFactory(PutObjectRequest putObjectRequest, String uploadId, long optimalPartSize, List<PartETag> alreadyUploadedParts) {
        this.putObjectRequest = putObjectRequest;
        this.uploadId = uploadId;
        this.optimalPartSize = optimalPartSize;
        this.bucketName = putObjectRequest.getBucketName();
        this.key = putObjectRequest.getKey();
        this.file = TransferManagerUtils.getRequestFile(putObjectRequest);
        this.remainingBytes = TransferManagerUtils.getContentLength(putObjectRequest);
        this.alreadyUploadedParts = alreadyUploadedParts;
    }
    
    public synchronized List<Long> getPreviouslyUpdatedBytesToSend() {
    	return previouslyUpdatedBytesToSend;
    }
    
    public synchronized void resetPreviouslyUpdatedBytesToSend() {
    	previouslyUpdatedBytesToSend = new ArrayList<Long>();
    }

    public synchronized boolean hasMoreRequests() {
        return (remainingBytes > 0);
    }

    public synchronized UploadPartRequest getNextUploadPartRequest() {
        long partSize = Math.min(optimalPartSize, remainingBytes);
        boolean isLastPart = (remainingBytes - partSize <= 0);

        UploadPartRequest request = null;
        
        if (alreadyUploaded(partNumber)) {
        	offset += partSize;
        	previouslyUpdatedBytes += partSize;
            remainingBytes -= partSize;
            partNumber++;
            
            return getNextUploadPartRequest();
        } else {
	        if (putObjectRequest.getInputStream() != null) {
	            request = new UploadPartRequest()
	                .withBucketName(bucketName)
	                .withKey(key)
	                .withUploadId(uploadId)
	                .withInputStream(new InputSubstream(putObjectRequest.getInputStream(), 0, partSize, isLastPart))
	                .withPartNumber(partNumber++)
	                .withPartSize(partSize);
	        } else {
	            request = new UploadPartRequest()
	                .withBucketName(bucketName)
	                .withKey(key)
	                .withUploadId(uploadId)
	                .withFile(file)
	                .withFileOffset(offset)
	                .withPartNumber(partNumber++)
	                .withPartSize(partSize);
	        }
	        
	        if (previouslyUpdatedBytes > 0) {
	        	previouslyUpdatedBytesToSend.add(previouslyUpdatedBytes);
	        	previouslyUpdatedBytes = 0;
	        }
        }

        offset += partSize;
        remainingBytes -= partSize;
        
        request.setLastPart(isLastPart);
        request.setGeneralProgressListener(putObjectRequest.getGeneralProgressListener());
        
        return request;
    }
    
    private boolean alreadyUploaded(int partNumber) {
    	if (alreadyUploadedParts == null) { return false; }
    	for (PartETag part : alreadyUploadedParts) {
    		if (part.getPartNumber() == partNumber) { return true; }
    	}
    	
    	return false;
    }
}
