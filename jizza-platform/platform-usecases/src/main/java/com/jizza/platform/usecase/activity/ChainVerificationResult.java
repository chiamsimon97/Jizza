package com.jizza.platform.usecase.activity;

import java.util.UUID;

public record ChainVerificationResult(boolean intact, UUID firstBrokenEntryId, long entries) {}
