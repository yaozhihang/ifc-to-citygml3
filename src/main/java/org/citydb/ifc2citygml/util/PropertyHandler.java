package org.citydb.ifc2citygml.util;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.ifc4.*;
import org.citygml4j.core.model.core.*;
import org.citygml4j.core.model.generics.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * Handles conversion of IFC properties to CityGML generic attributes.
 */
public class PropertyHandler {

    private static final Logger logger = LoggerFactory.getLogger(PropertyHandler.class);

    private final IfcModelInterface model;
    private final boolean noProperties;
    private final boolean noGenericAttributeSets;
    private final boolean setNamesAsPrefixes;

    // Property set lookup (element -> list of property set definitions)
    // Built from IfcRelDefinesByProperties since BIMServer doesn't populate inverse relationships
    private final Map<IfcObject, List<IfcPropertySetDefinitionSelect>> propertySetMap = new HashMap<>();

    // Registered handlers for predefined property set types (e.g., IfcWindowLiningProperties)
    private final List<BiPredicate<AbstractCityObject, IfcPropertySetDefinitionSelect>> predefinedPsetHandlers = new ArrayList<>();

    public PropertyHandler(IfcModelInterface model, boolean noProperties,
                           boolean noGenericAttributeSets, boolean setNamesAsPrefixes) {
        this.model = model;
        this.noProperties = noProperties;
        this.noGenericAttributeSets = noGenericAttributeSets;
        this.setNamesAsPrefixes = setNamesAsPrefixes;
    }

    /**
     * Registers a handler for predefined property set types (e.g., IfcWindowLiningProperties).
     * The handler returns true if it handled the property definition.
     */
    public void registerPredefinedPsetHandler(BiPredicate<AbstractCityObject, IfcPropertySetDefinitionSelect> handler) {
        predefinedPsetHandlers.add(handler);
    }

    /**
     * Builds the property set lookup map from IfcRelDefinesByProperties and IfcRelDefinesByType.
     * BIMserver's STEP deserializer doesn't populate inverse relationships (IsDefinedBy),
     * so we query relationship classes directly.
     */
    public void buildPropertySetMap() {
        // Instance-level properties via IfcRelDefinesByProperties
        List<IfcRelDefinesByProperties> rels = model.getAll(IfcRelDefinesByProperties.class);
        for (IfcRelDefinesByProperties rel : rels) {
            IfcPropertySetDefinitionSelect propDef = rel.getRelatingPropertyDefinition();
            if (propDef == null) continue;

            for (IfcObjectDefinition obj : rel.getRelatedObjects()) {
                if (obj instanceof IfcObject) {
                    propertySetMap.computeIfAbsent((IfcObject) obj, k -> new ArrayList<>()).add(propDef);
                }
            }
        }

        // Type-level properties via IfcRelDefinesByType -> IfcTypeObject -> HasPropertySets
        List<IfcRelDefinesByType> typeRels = model.getAll(IfcRelDefinesByType.class);
        for (IfcRelDefinesByType rel : typeRels) {
            IfcTypeObject typeObj = rel.getRelatingType();
            if (typeObj == null || !typeObj.isSetHasPropertySets()) continue;

            for (IfcObject obj : rel.getRelatedObjects()) {
                if (obj != null) {
                    for (IfcPropertySetDefinition setDef : typeObj.getHasPropertySets()) {
                        propertySetMap.computeIfAbsent(obj, k -> new ArrayList<>()).add(setDef);
                    }
                }
            }
        }

        logger.info("Property set map: {} elements with properties (from {} instance rels, {} type rels)",
            propertySetMap.size(), rels.size(), typeRels.size());
    }

    /**
     * Adds IFC properties as CityGML generic attributes
     */
    public void addProperties(AbstractCityObject cityObject, IfcRoot ifcElement) {
        if (noProperties) {
            return;
        }

        if (!(ifcElement instanceof IfcObject ifcObject)) {
            return;
        }

        List<IfcPropertySetDefinitionSelect> propDefs = propertySetMap.get(ifcObject);
        if (propDefs == null || propDefs.isEmpty()) {
            return;
        }

        for (IfcPropertySetDefinitionSelect propDef : propDefs) {
            if (propDef instanceof IfcPropertySet) {
                addPropertySet(cityObject, (IfcPropertySet) propDef);
            } else if (propDef instanceof IfcElementQuantity) {
                addElementQuantity(cityObject, (IfcElementQuantity) propDef);
            } else {
                // Delegate to registered handlers for predefined property set types
                for (var handler : predefinedPsetHandlers) {
                    if (handler.test(cityObject, propDef)) break;
                }
            }
        }
    }

    /**
     * Adds an IFC property set as a CityGML GenericAttributeSet
     */
    private void addPropertySet(AbstractCityObject cityObject, IfcPropertySet pset) {
        String psetName = pset.getName() != null ? pset.getName() : "UnnamedPropertySet";
        List<AbstractGenericAttributeProperty> attributes = new ArrayList<>();

        for (IfcProperty prop : pset.getHasProperties()) {
            String propName = prop.getName();
            if (propName == null || propName.equals("id")) continue;

            AbstractGenericAttribute<?> attr = null;

            if (prop instanceof IfcPropertySingleValue) {
                IfcValue value = ((IfcPropertySingleValue) prop).getNominalValue();
                if (value != null) {
                    attr = convertIfcValueToAttribute(propName, value);
                }
            } else if (prop instanceof IfcPropertyEnumeratedValue ev) {
                if (ev.isSetEnumerationValues()) {
                    StringBuilder sb = new StringBuilder();
                    for (IfcValue val : ev.getEnumerationValues()) {
                        if (!sb.isEmpty()) sb.append(", ");
                        sb.append(ifcValueToString(val));
                    }
                    attr = new StringAttribute(propName, sb.toString());
                }
            } else if (prop instanceof IfcPropertyBoundedValue bv) {
                IfcValue upper = bv.getUpperBoundValue();
                IfcValue lower = bv.getLowerBoundValue();
                if (upper != null) {
                    attr = convertIfcValueToAttribute(propName, upper);
                } else if (lower != null) {
                    attr = convertIfcValueToAttribute(propName, lower);
                }
            } else if (prop instanceof IfcPropertyListValue lv) {
                if (lv.isSetListValues()) {
                    StringBuilder sb = new StringBuilder();
                    for (IfcValue val : lv.getListValues()) {
                        if (!sb.isEmpty()) sb.append(", ");
                        sb.append(ifcValueToString(val));
                    }
                    attr = new StringAttribute(propName, sb.toString());
                }
            } else if (prop instanceof IfcPropertyTableValue tv) {
                StringBuilder sb = new StringBuilder();
                if (tv.isSetDefiningValues()) {
                    for (IfcValue val : tv.getDefiningValues()) {
                        if (!sb.isEmpty()) sb.append(", ");
                        sb.append(ifcValueToString(val));
                    }
                }
                if (!sb.isEmpty()) {
                    attr = new StringAttribute(propName, sb.toString());
                }
            } else if (prop instanceof IfcPropertyReferenceValue rv) {
                if (rv.getPropertyReference() != null) {
                    attr = new StringAttribute(propName, rv.getPropertyReference().toString());
                }
            }

            if (attr != null) {
                if (noGenericAttributeSets) {
                    String name = setNamesAsPrefixes ? "[" + psetName + "]" + propName : propName;
                    attr.setName(name);
                    cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attr));
                } else {
                    attributes.add(new AbstractGenericAttributeProperty(attr));
                }
            }
        }

        if (!noGenericAttributeSets && !attributes.isEmpty()) {
            GenericAttributeSet attrSet = new GenericAttributeSet();
            attrSet.setName(psetName);
            attrSet.setValue(attributes);
            cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attrSet));
        }
    }

    /**
     * Adds an IFC element quantity set as a CityGML GenericAttributeSet
     */
    private void addElementQuantity(AbstractCityObject cityObject, IfcElementQuantity eq) {
        String qtoName = eq.getName() != null ? eq.getName() : "UnnamedQuantitySet";
        List<AbstractGenericAttributeProperty> attributes = new ArrayList<>();

        for (IfcPhysicalQuantity qty : eq.getQuantities()) {
            String qtyName = qty.getName();
            if (qtyName == null) continue;

            AbstractGenericAttribute<?> attr = null;
            if (qty instanceof IfcQuantityLength) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityLength) qty).getLengthValue());
            } else if (qty instanceof IfcQuantityArea) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityArea) qty).getAreaValue());
            } else if (qty instanceof IfcQuantityVolume) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityVolume) qty).getVolumeValue());
            } else if (qty instanceof IfcQuantityCount) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityCount) qty).getCountValue());
            } else if (qty instanceof IfcQuantityWeight) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityWeight) qty).getWeightValue());
            } else if (qty instanceof IfcQuantityTime) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityTime) qty).getTimeValue());
            }

            if (attr != null) {
                if (noGenericAttributeSets) {
                    String name = setNamesAsPrefixes ? "[" + qtoName + "]" + qtyName : qtyName;
                    attr.setName(name);
                    cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attr));
                } else {
                    attributes.add(new AbstractGenericAttributeProperty(attr));
                }
            }
        }

        if (!noGenericAttributeSets && !attributes.isEmpty()) {
            GenericAttributeSet attrSet = new GenericAttributeSet();
            attrSet.setName(qtoName);
            attrSet.setValue(attributes);
            cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attrSet));
        }
    }

    /**
     * Adds a predefined property set as a GenericAttributeSet (or flat attributes).
     * Public so that domain-specific handlers can reuse the formatting logic.
     */
    public void addPredefinedPset(AbstractCityObject cityObject, String psetName,
                                    List<AbstractGenericAttributeProperty> attributes) {
        if (attributes.isEmpty()) return;

        if (noGenericAttributeSets) {
            for (AbstractGenericAttributeProperty attr : attributes) {
                if (setNamesAsPrefixes) {
                    AbstractGenericAttribute<?> ga = attr.getObject();
                    if (ga != null) ga.setName("[" + psetName + "]" + ga.getName());
                }
                cityObject.getGenericAttributes().add(attr);
            }
        } else {
            GenericAttributeSet attrSet = new GenericAttributeSet();
            attrSet.setName(psetName);
            attrSet.setValue(attributes);
            cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attrSet));
        }
    }

    /**
     * Converts an IFC value to a CityGML generic attribute
     */
    private AbstractGenericAttribute<?> convertIfcValueToAttribute(String name, IfcValue value) {
        if (value instanceof IfcBoolean) {
            Tristate ts = ((IfcBoolean) value).getWrappedValue();
            return new IntAttribute(name, ts == Tristate.TRUE ? 1 : 0);
        } else if (value instanceof IfcLogical) {
            Tristate ts = ((IfcLogical) value).getWrappedValue();
            return new IntAttribute(name, ts == Tristate.TRUE ? 1 : 0);
        } else if (value instanceof IfcInteger) {
            return new IntAttribute(name, (int) ((IfcInteger) value).getWrappedValue());
        } else if (value instanceof IfcReal) {
            return new DoubleAttribute(name, ((IfcReal) value).getWrappedValue());
        } else if (value instanceof IfcLabel) {
            String s = ((IfcLabel) value).getWrappedValue();
            return s != null ? new StringAttribute(name, s) : null;
        } else if (value instanceof IfcText) {
            String s = ((IfcText) value).getWrappedValue();
            return s != null ? new StringAttribute(name, s) : null;
        } else if (value instanceof IfcIdentifier) {
            String s = ((IfcIdentifier) value).getWrappedValue();
            return s != null ? new StringAttribute(name, s) : null;
        } else if (value instanceof IfcLengthMeasure) {
            return new DoubleAttribute(name, ((IfcLengthMeasure) value).getWrappedValue());
        } else if (value instanceof IfcAreaMeasure) {
            return new DoubleAttribute(name, ((IfcAreaMeasure) value).getWrappedValue());
        } else if (value instanceof IfcVolumeMeasure) {
            return new DoubleAttribute(name, ((IfcVolumeMeasure) value).getWrappedValue());
        } else if (value instanceof IfcCountMeasure) {
            return new DoubleAttribute(name, ((IfcCountMeasure) value).getWrappedValue());
        } else if (value instanceof IfcPlaneAngleMeasure) {
            return new DoubleAttribute(name, ((IfcPlaneAngleMeasure) value).getWrappedValue());
        } else if (value instanceof IfcMassMeasure) {
            return new DoubleAttribute(name, ((IfcMassMeasure) value).getWrappedValue());
        } else if (value instanceof IfcPowerMeasure) {
            return new DoubleAttribute(name, ((IfcPowerMeasure) value).getWrappedValue());
        } else if (value instanceof IfcThermalTransmittanceMeasure) {
            return new DoubleAttribute(name, ((IfcThermalTransmittanceMeasure) value).getWrappedValue());
        } else if (value instanceof IfcThermodynamicTemperatureMeasure) {
            return new DoubleAttribute(name, ((IfcThermodynamicTemperatureMeasure) value).getWrappedValue());
        } else if (value instanceof IfcMeasureValue) {
            // Generic fallback for all remaining measure types
            try {
                java.lang.reflect.Method m = value.getClass().getMethod("getWrappedValue");
                Object result = m.invoke(value);
                if (result instanceof Number) {
                    return new DoubleAttribute(name, ((Number) result).doubleValue());
                }
            } catch (Exception e) {
                // Fall through to string
            }
            return new StringAttribute(name, value.toString());
        } else {
            // Fallback: try to get string representation
            return new StringAttribute(name, value.toString());
        }
    }

    /**
     * Converts an IFC value to its string representation
     */
    private String ifcValueToString(IfcValue value) {
        if (value instanceof IfcLabel) return ((IfcLabel) value).getWrappedValue();
        if (value instanceof IfcText) return ((IfcText) value).getWrappedValue();
        if (value instanceof IfcIdentifier) return ((IfcIdentifier) value).getWrappedValue();
        if (value instanceof IfcBoolean) return ((IfcBoolean) value).getWrappedValue() == Tristate.TRUE ? "True" : "False";
        if (value instanceof IfcInteger) return String.valueOf(((IfcInteger) value).getWrappedValue());
        if (value instanceof IfcReal) return String.valueOf(((IfcReal) value).getWrappedValue());
        return value.toString();
    }
}
