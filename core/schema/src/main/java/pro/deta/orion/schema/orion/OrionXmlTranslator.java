package pro.deta.orion.schema.orion;

import java.io.IOException;

interface OrionXmlTranslator {
    OrionXmlSchemaVersion schemaVersion();

    OrionDocument read(byte[] content) throws IOException;
}
