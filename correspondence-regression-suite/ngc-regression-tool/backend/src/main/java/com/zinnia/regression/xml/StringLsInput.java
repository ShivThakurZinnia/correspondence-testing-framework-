package com.zinnia.regression.xml;

import java.io.InputStream;
import java.io.Reader;
import org.w3c.dom.ls.LSInput;

/** In-memory schema resource handed to the XSD validator for xs:include / xs:import. */
final class StringLsInput implements LSInput {

    private String publicId;
    private String systemId;
    private String baseUri;
    private String data;

    StringLsInput(String publicId, String systemId, String data) {
        this.publicId = publicId;
        this.systemId = systemId;
        this.data = data;
    }

    @Override public Reader getCharacterStream() { return null; }
    @Override public void setCharacterStream(Reader characterStream) { }
    @Override public InputStream getByteStream() { return null; }
    @Override public void setByteStream(InputStream byteStream) { }
    @Override public String getStringData() { return data; }
    @Override public void setStringData(String stringData) { this.data = stringData; }
    @Override public String getSystemId() { return systemId; }
    @Override public void setSystemId(String systemId) { this.systemId = systemId; }
    @Override public String getPublicId() { return publicId; }
    @Override public void setPublicId(String publicId) { this.publicId = publicId; }
    @Override public String getBaseURI() { return baseUri; }
    @Override public void setBaseURI(String baseURI) { this.baseUri = baseURI; }
    @Override public String getEncoding() { return null; }
    @Override public void setEncoding(String encoding) { }
    @Override public boolean getCertifiedText() { return false; }
    @Override public void setCertifiedText(boolean certifiedText) { }
}
