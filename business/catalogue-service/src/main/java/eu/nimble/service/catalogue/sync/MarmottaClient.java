package eu.nimble.service.catalogue.sync;

import eu.nimble.data.transformer.ontmalizer.XML2OWLMapper;
import eu.nimble.data.transformer.ontmalizer.XSD2OWLMapper;
import eu.nimble.service.catalogue.CatalogueServiceImpl;
import eu.nimble.service.catalogue.exception.CatalogueServiceException;
import eu.nimble.service.catalogue.util.ConfigUtil;
import eu.nimble.service.model.ubl.catalogue.CatalogueType;
import eu.nimble.utility.Configuration;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import static eu.nimble.service.catalogue.util.ConfigUtil.CONFIG_CATALOGUE_PERSISTENCE_MARMOTTA_URL;

/**
 * Created by suat on 02-Oct-17.
 */
public class MarmottaClient {
    private static final Logger logger = LoggerFactory.getLogger(MarmottaClient.class);

    public void submitCatalogueDataToMarmotta(CatalogueType catalogue) {
        logger.info("Catalogue with uuid: {} will be submitted to Marmotta.", catalogue.getUUID());
        XML2OWLMapper rdfGenerator = transformCatalogueToRDF(catalogue);
        logger.info("Transformed catalogue with uuid: {} to RDF", catalogue.getUUID());

        URL marmottaURL;
        try {
            String marmottaBaseUrl = ConfigUtil.getInstance().getConfig(CONFIG_CATALOGUE_PERSISTENCE_MARMOTTA_URL);
            marmottaURL = new URL(marmottaBaseUrl + "/import/upload?context=" + catalogue.getUUID());
        } catch (MalformedURLException e) {
            throw new CatalogueServiceException("Invalid format for the submitted template", e);
        } catch (IOException e) {
            throw new CatalogueServiceException("Failed to read Marmotta URL from config file", e);
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) marmottaURL.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/n3");
            conn.setDoOutput(true);
            conn.setConnectTimeout(18000);
            conn.setReadTimeout(18000);

            OutputStream os = conn.getOutputStream();
            rdfGenerator.writeModel(os, "N3");
            os.flush();

            logger.info("Catalogue with uuid: {} submitted to Marmotta. Received HTTP response: {}", catalogue.getUUID(), conn.getResponseCode());
            if (conn.getResponseCode() == 500) {
                InputStream error = conn.getErrorStream();
                logger.error("Error from Marmotta: " + IOUtils.toString(error));
            }

            conn.disconnect();
        } catch (IOException e) {
            throw new CatalogueServiceException("Failed to submit catalogue to Marmotta", e);
        }
    }

    public void deleteCatalogueFromMarmotta(String uuid) {
        logger.info("Catalogue with uuid: {} will be deleted from Marmotta", uuid);

        URL marmottaURL;
        try {
            String marmottaBaseUrl = ConfigUtil.getInstance().getConfig(CONFIG_CATALOGUE_PERSISTENCE_MARMOTTA_URL);
            marmottaURL = new URL(marmottaBaseUrl + "/context/" + uuid);
        } catch (IOException e) {
            throw new CatalogueServiceException("Failed to read Marmotta URL from config file", e);
        }

        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) marmottaURL.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setDoOutput(true);
            conn.setConnectTimeout(18000);
            conn.setReadTimeout(18000);

            OutputStream os = conn.getOutputStream();
            os.flush();

            logger.info("Marmotta response for deleting catalogue with uuid: {}: {}", uuid, conn.getResponseCode());

            conn.disconnect();
        } catch (IOException e) {
            throw new CatalogueServiceException("Failed to submit catalogue to Marmotta", e);
        }
        logger.info("Catalogue with uuid: {} deleted from Marmotta", uuid);
    }

    private XML2OWLMapper transformCatalogueToRDF(CatalogueType catalogue) {
        // TODO generate the ontology once, once the data model is finalized
        XSD2OWLMapper mapping = getXSDToOWLMapping();

        ByteArrayOutputStream serializedCatalogueBaos = new ByteArrayOutputStream();
        StringWriter serializedCatalogueWriter = new StringWriter();
        try {
            String packageName = catalogue.getClass().getPackage().getName();
            JAXBContext jc = JAXBContext.newInstance(packageName);

            Marshaller marsh = jc.createMarshaller();
            marsh.setProperty("jaxb.formatted.output", true);
            JAXBElement element = new JAXBElement(
                    new QName(Configuration.UBL_CATALOGUE_NS, "Catalogue"), catalogue.getClass(), catalogue);
            marsh.marshal(element, serializedCatalogueBaos);
            marsh.marshal(element, serializedCatalogueWriter);

        } catch (JAXBException e) {
            throw new CatalogueServiceException("Failed to serialize the catalogue instance to XML", e);
        }

        // log the catalogue to be transformed
        logger.debug("Catalogue to be transformed:\n{}", serializedCatalogueWriter.toString());
        serializedCatalogueWriter.flush();

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(serializedCatalogueBaos.toByteArray());
            serializedCatalogueBaos.flush();
            XML2OWLMapper generator = new XML2OWLMapper(bais, mapping);
            generator.convertXML2OWL();

            serializedCatalogueBaos.close();
            bais.close();

            StringWriter catalogueRDFWriter = new StringWriter();
            generator.writeModel(catalogueRDFWriter, "N3");
            logger.debug("Transformed RDF data:\n{}", catalogueRDFWriter.toString());
            catalogueRDFWriter.flush();

            return generator;

        } catch (IOException e) {
            throw new CatalogueServiceException("Failed to convert catalogue with uuid " + catalogue.getUUID() + " to RDF", e);
        }
    }

    private XSD2OWLMapper getXSDToOWLMapping() {
        URL url = CatalogueServiceImpl.class.getResource(Configuration.UBL_CATALOGUE_SCHEMA);
        XSD2OWLMapper mapping = new XSD2OWLMapper(url);
        mapping.setObjectPropPrefix("");
        mapping.setDataTypePropPrefix("");
        mapping.convertXSD2OWL();

        // log the ontology generated based on the XSD schema
        StringWriter serializedOntology = new StringWriter();
        mapping.writeOntology(serializedOntology, "N3");
        //logger.debug("Serialized ontology:\n{}", serializedOntology.toString());
        //serializedOntology.flush();

        return mapping;
    }
}