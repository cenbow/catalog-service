package eu.nimble.service.catalogue.category.taxonomy.eclass.database;

import eu.nimble.service.catalogue.category.datamodel.Category;
import eu.nimble.service.catalogue.category.datamodel.Property;
import eu.nimble.service.catalogue.category.datamodel.Unit;
import eu.nimble.service.catalogue.category.datamodel.Value;
import eu.nimble.utility.config.CatalogueServiceConfig;
import eu.nimble.service.catalogue.exception.CategoryDatabaseException;
import eu.nimble.service.catalogue.template.TemplateConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

import static eu.nimble.service.catalogue.category.taxonomy.eclass.database.EClassCategoryDatabaseConfig.*;

/**
 * Created by suat on 03-Mar-17.
 */
public class EClassCategoryDatabaseAdapter {
    private static final Logger logger = LoggerFactory.getLogger(EClassCategoryDatabaseAdapter.class);
    private static final String CATEGORY_BASE_URI = "http://www.nimble-project.org/resource/eclass/";
    private static final String PROPERTY_BASE_URI = "http://www.nimble-project.org/resource/eclass/property/";

    public static void main(String[] args) throws CategoryDatabaseException, SQLException, ClassNotFoundException {
        /*EClassCategoryDatabaseAdapter e = new EClassCategoryDatabaseAdapter();
        Connection c = e.getConnection();
        List<Category> results = e.getClassificationClassesByName("yarn");
        //List<Category> results = e.getClassificationClassesByLevel(1);
        //List<Category> results = e.getSubCategories("0173-1#01-ADG629#002");
        for (Category cc : results) {
            System.out.println(cc.getPreferredName());
        }
        c.close();*/
    }

    private Connection getConnection() throws CategoryDatabaseException {
        try {
            CatalogueServiceConfig config = CatalogueServiceConfig.getInstance();

            Class.forName(config.getCategoryDbDriver());
            Connection connection = DriverManager
                    .getConnection(config.getCategoryDbConnectionUrl(), config.getCategoryDbUsername(), config.getCategoryDbPassword());

            PreparedStatement preparedStatement = connection.prepareStatement(eClassQuerySetPostgresDatabaseSchema(config.getCategoryDbScheme()));
            preparedStatement.execute();

            return connection;

        } catch (SQLException | ClassNotFoundException e) {
            throw new CategoryDatabaseException("Failed to get connection", e);
        }

        /*Class.forName(PRODUCT_CATEGORY_H2_CONFIG_DRIVER);
        Connection connection = DriverManager
                .getConnection(PRODUCT_CATEGORY_H2_CONFIG_URL, PRODUCT_CATEGORY_H2_CONFIG_USER, PRODUCT_CATEGORY_H2_CONFIG_PASSWORD);*/
        // check h2 if there is metadata about a table to deduce whether it exists in the H2 DB already
        /*ResultSet meta = connection.getMetaData().getTables(null, null, TABLE_NAME_CLASSIFICATION_CLASS, null);
        if(meta.next() == false) {
            InputStream is = EClassCategoryDatabaseAdapter.class.getResourceAsStream("/eClassLoader.sql");
            try {
                String initScript = IOUtils.toString(is);
                initScript = initScript.replace("{dataPath}", EClassCategoryDatabaseConfig.PRODUCT_CATEGORY_DATA_PATH);
                Statement stmt = connection.createStatement();
                stmt.execute(initScript);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }*/
    }

    private void closeConnection(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.error("Failed to close connection", e);
        }
    }

    public Category getCategoryById(String categoryId) throws CategoryDatabaseException {
        Connection connection = null;
        List<Category> results = new ArrayList<>();

        try {
            connection = getConnection();
            PreparedStatement preparedStatement = connection.prepareStatement(eClassQueryGetClassificationClassByIdList(Arrays.asList(new String[]{categoryId})));
            preparedStatement.setString(1, categoryId);
            ResultSet rs = preparedStatement.executeQuery();
            Category cc = extractClassificationClassesFromResultSet(rs).get(0);
            rs.close();
            preparedStatement.close();

            return cc;
        } catch (SQLException e) {
            throw new CategoryDatabaseException("Failed to retrieve classification by level", e);
        } finally {
            closeConnection(connection);
        }
    }

    /**
     * Takes a {@code categoryName} and returns a list of {@link Category} including potential categories
     * matching with the given name. The method first checks the names of classification class to match the given name;
     * then additional results are added based on the equivalent keywords of classes
     *
     * @param categoryName
     * @return
     * @throws CategoryDatabaseException
     */
    public List<Category> getClassificationClassesByName(String categoryName) throws CategoryDatabaseException {
        Connection connection = null;
        try {
            List<Category> allResults = new ArrayList<>();

            connection = getConnection();
            // first retrieve results from the classification list
            allResults = getClassificationClassesByPreferredName(connection, categoryName);

            // then retrieve the results based on the equivalent keywords
            allResults.addAll(getClassificationClassesByKeywords(connection, categoryName));

            // include only the leaf level classes in the result set
            List<Category> results = new ArrayList<>();
            for(Category category : allResults) {
                if(category.getLevel() == 4) {
                    results.add(category);
                }
            }

            return results;
        } finally {
            closeConnection(connection);
        }
    }

    private List<Category> getClassificationClassesByPreferredName(Connection connection, String categoryName) throws CategoryDatabaseException {
        List<Category> results = new ArrayList<>();

        try {
            PreparedStatement preparedStatement = connection.prepareStatement(eClassQueryGetClassificationClassByName());
            preparedStatement.setString(1, "%" + categoryName + "%");
            ResultSet rs = preparedStatement.executeQuery();
            results = extractClassificationClassesFromResultSet(rs);
            rs.close();
            preparedStatement.close();
        } catch (SQLException e) {
            throw new CategoryDatabaseException("Failed to retrieve classification by preferred name", e);
        }
        return results;
    }

    private List<Category> getClassificationClassesByKeywords(Connection connection, String categoryName) throws CategoryDatabaseException {
        List<Category> results = new ArrayList<>();

        try {
            // Get the identifiers of the categories of which keywords match with the given category name
            PreparedStatement preparedStatement = connection.prepareStatement(eClassQueryGetKeywordByValue());
            preparedStatement.setString(1, "%" + categoryName + "%");
            ResultSet rs = preparedStatement.executeQuery();

            List<String> classIds = new ArrayList<>();
            while (rs.next()) {
                classIds.add(rs.getString(COLUMN_KEYWORD_SYNONYM_IRDI_TARGET));
            }
            rs.close();
            preparedStatement.close();

            if (classIds.size() > 0) {
                // get the classification classes based on the retrieved ids
                preparedStatement = connection.prepareStatement(eClassQueryGetClassificationClassByIdList(classIds));
                for (int i = 0; i < classIds.size(); i++) {
                    preparedStatement.setString(i + 1, classIds.get(i));
                }
                rs = preparedStatement.executeQuery();
                results = extractClassificationClassesFromResultSet(rs);
                rs.close();
                preparedStatement.close();
            }

        } catch (SQLException e) {
            throw new CategoryDatabaseException("Failed to retrieve classification by preferred name", e);
        }
        return results;
    }

    public List<Category> getClassificationClassesByLevel(int level) throws CategoryDatabaseException {
        Connection connection = null;
        List<Category> results = new ArrayList<>();

        try {
            connection = getConnection();

            PreparedStatement preparedStatement = connection.prepareStatement(eClassQueryGetClassificationClassByLevel());
            preparedStatement.setString(1, Integer.toString(level));
            ResultSet rs = preparedStatement.executeQuery();
            results = extractClassificationClassesFromResultSet(rs);
            rs.close();
            preparedStatement.close();

            return results;
        } catch (SQLException e) {
            throw new CategoryDatabaseException("Failed to retrieve classification by level", e);
        } finally {
            closeConnection(connection);
        }
    }

    public List<Category> getSubCategories(String parentId) throws CategoryDatabaseException {
        Connection connection = null;
        List<Category> results = new ArrayList<>();

        try {
            connection = getConnection();
            Category cc = getCategoryById(parentId);

            PreparedStatement preparedStatement = connection.prepareStatement(eClassQueryGetSubCategoryIds());
            preparedStatement.setString(1, Integer.toString(cc.getLevel() + 1));
            preparedStatement.setString(2, cc.getCode().substring(0, 2) + "%");
            ResultSet rs = preparedStatement.executeQuery();
            results = extractClassificationClassesFromResultSet(rs);
            rs.close();
            preparedStatement.close();

            return results;
        } catch (SQLException e) {
            throw new CategoryDatabaseException("Failed to retrieve classification by level", e);
        } finally {
            closeConnection(connection);
        }
    }

    public List<Property> getPropertiesForCategory(String categoryId) throws CategoryDatabaseException {
        Connection connection = null;

        try {
            connection = getConnection();

            // get properties without the unit and allowed values. They are queried separately.
            Map<String, Property> properties;
            PreparedStatement preparedStatement = connection.prepareStatement(eClassQueryGetPossiblePropertiesForCategory());
            preparedStatement.setString(1, categoryId);
            ResultSet rs = preparedStatement.executeQuery();
            properties = extractPropertiesFromResultSet(rs);
            rs.close();
            preparedStatement.close();

            // get allowed values for properties
            preparedStatement = connection.prepareStatement(eClassQueryGetValuesForProperties());
            preparedStatement.setString(1, categoryId);
            rs = preparedStatement.executeQuery();
            setAllowedValuesToProperties(properties, rs);
            rs.close();
            preparedStatement.close();

            // get units values for properties
            preparedStatement = connection.prepareStatement(eClassQueryGetUnitsForProperties());
            preparedStatement.setString(1, categoryId);
            rs = preparedStatement.executeQuery();
            setUnitsToProperties(properties, rs);
            rs.close();
            preparedStatement.close();

            return new ArrayList<>(properties.values());
        } catch (SQLException e) {
            throw new CategoryDatabaseException("Failed to retrieve properties for the category", e);
        } finally {
            closeConnection(connection);
        }
    }

    private List<Category> extractClassificationClassesFromResultSet(ResultSet rs) throws SQLException {
        List<Category> results = new ArrayList<>();
        while (rs.next()) {
            Category cc = new Category();
            cc.setCode(rs.getString(COLUMN_CLASSIFICATION_CLASS_CODED_NAME));
            cc.setDefinition(rs.getString(COLUMN_CLASSIFICATION_CLASS_DEFINITION));
            cc.setId(rs.getString(COLUMN_CLASSIFICATION_CLASS_IRDICC));
            cc.setLevel(Integer.valueOf(rs.getString(COLUMN_CLASSIFICATION_CLASS_LEVEL)));
            cc.setPreferredName(rs.getString(COLUMN_CLASSIFICATION_CLASS_PREFERRED_NAME));
            cc.setNote(rs.getString(COLUMN_CLASSIFICATION_CLASS_NOTE));
            cc.setRemark(rs.getString(COLUMN_CLASSIFICATION_CLASS_REMARK));
            cc.setTaxonomyId("eClass");
            cc.setCategoryUri(CATEGORY_BASE_URI + cc.getCode());
            results.add(cc);
        }
        return results;
    }

    private Map<String, Property> extractPropertiesFromResultSet(ResultSet rs) throws SQLException {
        Map<String, Property> results = new LinkedHashMap<>();
        while (rs.next()) {
            Property prop = new Property();
            prop.setId(rs.getString(COLUMN_PROPERTY_IRDI_PR));
            prop.setPreferredName(rs.getString(COLUMN_PROPERTY_PREFERRED_NAME));
            prop.setShortName(rs.getString(COLUMN_PROPERTY_SHORT_NAME));
            prop.setDefinition(rs.getString(COLUMN_PROPERTY_DEFINITION));
            prop.setNote(rs.getString(COLUMN_PROPERTY_NOTE));
            prop.setRemark(rs.getString(COLUMN_PROPERTY_REMARK));
            prop.setPreferredSymbol(rs.getString(COLUMN_PROPERTY_PREFERRED_SYMBOL));
            prop.setIecCategory(rs.getString(COLUMN_PROPERTY_CATEGORY));
            prop.setAttributeType(rs.getString(COLUMN_PROPERTY_ATTRIBUTE_TYPE));
            prop.setDataType(getNormalizedDatatype(rs.getString(COLUMN_PROPERTY_DATA_TYPE)));
            prop.setUri(PROPERTY_BASE_URI + rs.getString(COLUMN_PROPERTY_IDPR));
            results.put(prop.getId(), prop);
        }
        return results;
    }

    private void setAllowedValuesToProperties(Map<String, Property> properties, ResultSet rs) throws SQLException {
        while (rs.next()) {
            String propId = rs.getString(COLUMN_CLASSIFICATION_CLASS_PROPERTY_VALUE_IRDI_PR);
            Property prop = properties.get(propId);

            Value value = new Value();
            value.setId(rs.getString(COLUMN_ECLASS_VALUE_IRDI_VA));
            value.setDataType(getNormalizedDatatype(rs.getString(COLUMN_ECLASS_VALUE_DATA_TYPE)));
            value.setShortName(rs.getString(COLUMN_ECLASS_VALUE_SHORT_NAME));
            value.setPreferredName(rs.getString(COLUMN_ECLASS_VALUE_PREFERRED_NAME));
            value.setDefinition(rs.getString(COLUMN_ECLASS_VALUE_DEFINITION));

            prop.addValue(value);
        }
    }

    private void setUnitsToProperties(Map<String, Property> properties, ResultSet rs) throws SQLException {
        while (rs.next()) {
            String propId = rs.getString(COLUMN_CLASSIFICATION_CLASS_PROPERTY_IRDI_PR);
            Property prop = properties.get(propId);

            Unit unit = new Unit();
            unit.setId(rs.getString(COLUMN_UNIT_IRDI_UN));
            unit.setDefinition(rs.getString(COLUMN_UNIT_DEFINITION));
            unit.setShortName(rs.getString(COLUMN_UNIT_SHORT_NAME));
            unit.setStructuredName(rs.getString(COLUMN_UNIT_STRUCTURED_NAMING));
            unit.setComment(rs.getString(COLUMN_UNIT_COMMENT));
            unit.setDinNotation(rs.getString(COLUMN_UNIT_DIN_NOTATION));
            unit.setEceCode(rs.getString(COLUMN_UNIT_ECE_CODE));
            unit.setEceName(rs.getString(COLUMN_UNIT_ECE_NAME));
            unit.setIecClassification(rs.getString(COLUMN_UNIT_IEC_CLASSIFICATION));
            unit.setNameOfDedicatedQuantity(rs.getString(COLUMN_UNIT_NAME_OF_DEDICATED_QUANTITY));
            unit.setNistName(rs.getString(COLUMN_UNIT_NIST_NAME));
            unit.setSiName(rs.getString(COLUMN_UNIT_SI_NAME));
            unit.setSiNotation(rs.getString(COLUMN_UNIT_SI_NOTATION));
            unit.setSource(rs.getString(COLUMN_UNIT_SOURCE));

            prop.setDataType("QUANTITY");
            prop.setUnit(unit);
        }
    }

    private String getNormalizedDatatype(String dataType) {

            String normalizedType;
            if (dataType.compareToIgnoreCase("INTEGER_COUNT") == 0 ||
                    dataType.compareToIgnoreCase("INTEGER_MEASURE") == 0 ||
                    dataType.compareToIgnoreCase("INTEGER_CURRENCY") == 0 ||
                    dataType.compareToIgnoreCase("REAL_COUNT") == 0 ||
                    dataType.compareToIgnoreCase(TemplateConfig.TEMPLATE_DATA_TYPE_REAL_MEASURE) == 0 ||
                    dataType.compareToIgnoreCase("REAL_CURRENCY") == 0 ||
                    dataType.compareToIgnoreCase("RATIONAL") == 0 ||
                    dataType.compareToIgnoreCase("RATIONAL_MEASURE") == 0) {
                normalizedType = TemplateConfig.TEMPLATE_DATA_TYPE_REAL_MEASURE;

            } else if (dataType.compareToIgnoreCase(TemplateConfig.TEMPLATE_DATA_TYPE_STRING) == 0 ||
                    dataType.compareToIgnoreCase(TemplateConfig.TEMPLATE_DATA_TYPE_STRING_TRANSLATABLE) == 0) {
                normalizedType = TemplateConfig.TEMPLATE_DATA_TYPE_STRING;

            } else if (dataType.compareToIgnoreCase(TemplateConfig.TEMPLATE_DATA_TYPE_BOOLEAN) == 0) {
                normalizedType = TemplateConfig.TEMPLATE_DATA_TYPE_BOOLEAN;

            } else {
                logger.warn("Unknown data type encountered: {}", dataType);
                normalizedType = TemplateConfig.TEMPLATE_DATA_TYPE_STRING;
            }
            return normalizedType;
    }
}
