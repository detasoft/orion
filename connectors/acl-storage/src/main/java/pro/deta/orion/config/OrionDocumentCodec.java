package pro.deta.orion.config;

import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Converts configuration documents to and from their schema-validated XML representation. */
public final class OrionDocumentCodec {
    public OrionDocument read(InputStream input) throws IOException {
        return OrionXml.read(input);
    }

    public void write(OrionDocument document, OutputStream output) throws IOException {
        OrionXml.write(document, output);
    }
}
