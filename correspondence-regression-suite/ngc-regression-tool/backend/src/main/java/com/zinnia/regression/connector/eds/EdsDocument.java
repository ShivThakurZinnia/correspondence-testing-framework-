package com.zinnia.regression.connector.eds;

public record EdsDocument(String documentId, String contentType, String filename, byte[] content) {
}
