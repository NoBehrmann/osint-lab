package com.osintlab.tools;

import com.osintlab.support.UploadStore;

/**
 * A tool that works on an uploaded file instead of a text target. The file is on disk
 * ({@link UploadStore.Upload#path()}) - tools stream it rather than loading it into memory, so large
 * videos are fine. The upload is shared by all tools of one investigation.
 */
public interface FileTool extends OsintTool {

    Object runFile(UploadStore.Upload file) throws Exception;

    @Override
    default Object run(String target) {
        throw new IllegalArgumentException("This tool needs an uploaded file");
    }
}
