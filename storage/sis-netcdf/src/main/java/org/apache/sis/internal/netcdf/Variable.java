/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.internal.netcdf;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;
import java.io.IOException;
import java.awt.image.DataBuffer;
import java.time.Instant;
import javax.measure.Unit;
import org.opengis.referencing.operation.Matrix;
import org.apache.sis.storage.DataStoreException;
import org.apache.sis.coverage.grid.GridExtent;
import org.apache.sis.math.Vector;
import org.apache.sis.math.MathFunctions;
import org.apache.sis.math.DecimalFunctions;
import org.apache.sis.measure.NumberRange;
import org.apache.sis.util.Numbers;
import org.apache.sis.util.ArraysExt;
import org.apache.sis.util.collection.WeakHashSet;
import org.apache.sis.internal.util.CollectionsExt;
import org.apache.sis.util.logging.WarningListeners;
import org.apache.sis.util.resources.Errors;
import ucar.nc2.constants.CDM;                      // We use only String constants.
import ucar.nc2.constants.CF;


/**
 * A netCDF variable created by {@link Decoder}.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @author  Johann Sorel (Geomatys)
 * @version 1.0
 * @since   0.3
 * @module
 */
public abstract class Variable extends NamedElement {
    /**
     * Pool of vectors created by the {@link #read()} method. This pool is used for sharing netCDF coordinate axes,
     * since the same vectors tend to be repeated in many netCDF files produced by the same data producer. Because
     * those vectors can be large, sharing common instances may save a lot of memory.
     *
     * <p>All shared vectors shall be considered read-only.</p>
     */
    protected static final WeakHashSet<Vector> SHARED_VECTORS = new WeakHashSet<>(Vector.class);

    /**
     * Names of attributes where to fetch minimum and maximum sample values, in preference order.
     *
     * @see #getValidValues()
     */
    private static final String[] RANGE_ATTRIBUTES = {
        "valid_range",      // Expected "reasonable" range for variable.
        "actual_range",     // Actual data range for variable.
        "valid_min",        // Fallback if "valid_range" is not specified.
        "valid_max"
    };

    /**
     * Names of attributes where to fetch missing or pad values. Order matter since it determines the bits to be set
     * in the map returned by {@link #getNodataValues()}. The main bit is bit 0, which identify the background value.
     */
    private static final String[] NODATA_ATTRIBUTES = {
        CDM.FILL_VALUE,
        CDM.MISSING_VALUE
    };

    /**
     * The pattern to use for parsing temporal units of the form "days since 1970-01-01 00:00:00".
     *
     * @see #parseUnit(String)
     * @see Decoder#numberToDate(String, Number[])
     */
    public static final Pattern TIME_UNIT_PATTERN = Pattern.compile("(.+)\\Wsince\\W(.+)", Pattern.CASE_INSENSITIVE);

    /**
     * The unit of measurement, parsed from {@link #getUnitsString()} when first needed.
     * We do not try to parse the unit at construction time because this variable may be
     * never requested by the user.
     *
     * @see #getUnit()
     */
    private Unit<?> unit;

    /**
     * If the unit is a temporal unit of the form "days since 1970-01-01 00:00:00", the epoch.
     * Otherwise {@code null}. This value can be set by subclasses as a side-effect of their
     * {@link #parseUnit(String)} method implementation.
     */
    protected Instant epoch;

    /**
     * Whether an attempt to parse the unit has already be done. This is used for avoiding
     * to report the same failure many times when {@link #unit} stay null.
     *
     * @see #getUnit()
     */
    private boolean unitParsed;

    /**
     * All no-data values declared for this variable, or an empty map if none.
     * This is computed by {@link #getNodataValues()} and cached for efficiency and stability.
     */
    private Map<Number,Integer> nodataValues;

    /**
     * Where to report warnings, if any.
     */
    private final WarningListeners<?> listeners;

    /**
     * Creates a new variable.
     *
     * @param listeners where to report warnings.
     */
    protected Variable(final WarningListeners<?> listeners) {
        this.listeners = listeners;
    }

    /**
     * Returns the name of the netCDF file containing this variable, or {@code null} if unknown.
     * This is used for information purpose or error message formatting only.
     *
     * @return name of the netCDF file containing this variable, or {@code null} if unknown.
     */
    public abstract String getFilename();

    /**
     * Returns the name of this variable, or {@code null} if none.
     *
     * @return the name of this variable, or {@code null}.
     */
    @Override
    public abstract String getName();

    /**
     * Returns the standard name if available, or the long name other, or the ordinary name otherwise.
     *
     * @return the standard name, or a fallback if there is no standard name.
     */
    public final String getStandardName() {
        String name = getAttributeAsString(CF.STANDARD_NAME);
        if (name == null) {
            name = getAttributeAsString(CDM.LONG_NAME);
            if (name == null) {
                name = getName();
            }
        }
        return name;
    }

    /**
     * Returns the description of this variable, or {@code null} if none.
     *
     * @return the description of this variable, or {@code null}.
     */
    public abstract String getDescription();

    /**
     * Returns the unit of measurement as a string, or {@code null} or an empty string if none.
     * The empty string can not be used for meaning "dimensionless unit"; some text is required.
     *
     * <p>Note: the UCAR library has its own API for handling units (e.g. {@link ucar.nc2.units.SimpleUnit}).
     * However as of November 2018, this API does not allow us to identify the quantity type except for some
     * special cases. We will parse the unit symbol ourselves instead, but we still need the full unit string
     * for parsing also its {@linkplain Axis#direction direction}.</p>
     *
     * @return the unit of measurement, or {@code null}.
     */
    protected abstract String getUnitsString();

    /**
     * Parses the given unit symbol and set the {@link #epoch} if the parsed unit is a temporal unit.
     *
     * @param  symbols  the unit symbol to parse.
     * @return the parsed unit.
     * @throws Exception if the unit can not be parsed. This wide exception type is used by the UCAR library.
     */
    protected abstract Unit<?> parseUnit(String symbols) throws Exception;

    /**
     * Sets the unit of measurement and the epoch to the same value than the given variable.
     * This method is not used in CF-compliant files; it is reserved for the handling of some
     * particular conventions, for example HYCOM.
     *
     * @param  other      the variable from which to copy unit and epoch, or {@code null} if none.
     * @param  overwrite  if non-null, set to the given unit instead than the unit of {@code other}.
     * @return the epoch (may be {@code null}).
     */
    public final Instant setUnit(final Variable other, Unit<?> overwrite) {
        if (other != null) {
            unit  = other.getUnit();        // May compute the epoch as a side effect.
            epoch = other.epoch;
        }
        if (overwrite != null) {
            unit = overwrite;
        }
        return epoch;
    }

    /**
     * Returns the unit of measurement for this variable, or {@code null} if unknown.
     * This method parse the units from {@link #getUnitsString()} when first needed
     * and sets {@link #epoch} as a side-effect if the unit is temporal.
     *
     * @return the unit of measurement, or {@code null}.
     */
    public final Unit<?> getUnit() {
        if (!unitParsed) {
            unitParsed = true;                          // Set first for avoiding to report errors many times.
            final String symbols = getUnitsString();
            if (symbols != null && !symbols.isEmpty()) try {
                unit = parseUnit(symbols);
            } catch (Exception ex) {
                error(Variable.class, "getUnit", ex, Errors.Keys.CanNotAssignUnitToVariable_2, getName(), symbols);
            }
        }
        return unit;
    }

    /**
     * Returns {@code true} if this variable contains data that are already in the unit of measurement represented by
     * {@link #getUnit()}, except for the fill/missing values. If {@code true}, then replacing fill/missing values by
     * {@code NaN} is the only action needed for having converted values.
     *
     * <p>This method is for detecting when {@link org.apache.sis.storage.netcdf.GridResource#getSampleDimensions()}
     * should return sample dimensions for already converted values. But to be consistent with {@code SampleDimension}
     * contract, it requires fill/missing values to be replaced by NaN. This is done by {@link #replaceNaN(Object)}.</p>
     *
     * @return whether this variable contains values in unit of measurement, ignoring fill and missing values.
     */
    public final boolean hasRealValues() {
        final int n = getDataType().number;
        if (n == Numbers.FLOAT | n == Numbers.DOUBLE) {
            double c = getAttributeAsNumber(CDM.SCALE_FACTOR);
            if (Double.isNaN(c) || c == 1) {
                c = getAttributeAsNumber(CDM.ADD_OFFSET);
                return Double.isNaN(c) || c == 0;
            }
        }
        return false;
    }

    /**
     * Returns the variable data type.
     *
     * @return the variable data type, or {@link DataType#UNKNOWN} if unknown.
     *
     * @see #getAttributeType(String)
     */
    public abstract DataType getDataType();

    /**
     * Returns the name of the variable data type as the name of the primitive type
     * followed by the span of each dimension (in unit of grid cells) between brackets.
     * Example: {@code "SHORT[180][360]"}.
     *
     * @return the name of the variable data type.
     */
    public final String getDataTypeName() {
        final StringBuilder buffer = new StringBuilder(20);
        buffer.append(getDataType().name().toLowerCase());
        final int[] shape = getShape();
        for (int i=shape.length; --i>=0;) {
            buffer.append('[').append(Integer.toUnsignedLong(shape[i])).append(']');
        }
        return buffer.toString();
    }

    /**
     * Returns whether this variable can grow. A variable is unlimited if at least one of its dimension is unlimited.
     * In netCDF 3 classic format, only the first dimension can be unlimited.
     *
     * @return whether this variable can grow.
     */
    public abstract boolean isUnlimited();

    /**
     * Returns {@code true} if the given variable can be used for generating an image.
     * This method checks for the following conditions:
     *
     * <ul>
     *   <li>Images require at least {@value Grid#MIN_DIMENSION} dimensions of size equals or greater than {@value Grid#MIN_SPAN}.
     *       They may have more dimensions, in which case a slice will be taken later.</li>
     *   <li>Exclude axes. Axes are often already excluded by the above condition because axis are usually 1-dimensional,
     *       but some axes are 2-dimensional (e.g. a localization grid).</li>
     *   <li>Excludes characters, strings and structures, which can not be easily mapped to an image type.
     *       In addition, 2-dimensional character arrays are often used for annotations and we do not want
     *       to confuse them with images.</li>
     * </ul>
     *
     * @return {@code true} if the variable can be considered a coverage.
     */
    public final boolean isCoverage() {
        int numVectors = 0;                                     // Number of dimension having more than 1 value.
        for (final int length : getShape()) {
            if (Integer.toUnsignedLong(length) >= Grid.MIN_SPAN) {
                numVectors++;
            }
        }
        if (numVectors >= Grid.MIN_DIMENSION) {
            final DataType dataType = getDataType();
            if (dataType.rasterDataType != DataBuffer.TYPE_UNDEFINED) {
                return !isCoordinateSystemAxis();
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if this variable seems to be a coordinate system axis instead than the actual data.
     * By netCDF convention, coordinate system axes have the name of one of the dimensions defined in the netCDF header.
     *
     * @return {@code true} if this variable seems to be a coordinate system axis.
     */
    public abstract boolean isCoordinateSystemAxis();

    /**
     * Returns the grid geometry for this variable, or {@code null} if this variable is not a data cube.
     * Not all variables have a grid geometry. For example collections of features do not have such grid.
     * The same grid geometry may be shared by many variables.
     *
     * @param  decoder  the decoder to use for constructing the grid geometry if needed.
     * @return the grid geometry for this variable, or {@code null} if none.
     * @throws IOException if an error occurred while reading the data.
     * @throws DataStoreException if a logical error occurred.
     */
    public abstract Grid getGridGeometry(Decoder decoder) throws IOException, DataStoreException;

    /**
     * Returns the names of the dimensions of this variable, in the order they are declared in the netCDF file.
     * The dimensions are those of the grid, not the dimensions of the coordinate system.
     * This information is used for completing ISO 19115 metadata.
     *
     * @return the names of all dimension of the grid, in netCDF order (reverse of "natural" order).
     */
    public abstract String[] getGridDimensionNames();

    /**
     * Returns the length (number of cells) of each grid dimension, in the order they are declared in the netCDF file.
     * The length of this array shall be equals to the length of the {@link #getGridDimensionNames()} array.
     * Values shall be handled as unsigned 32 bits integers.
     *
     * <p>In ISO 19123 terminology, this method returns the upper corner of the grid envelope plus one.
     * The lower corner is always (0, 0, …, 0). This method is used by {@link #isCoverage()} method,
     * or for building string representations of this variable.</p>
     *
     * @return the number of grid cells for each dimension, as unsigned integer in netCDF order (reverse of "natural" order).
     *
     * @see Grid#getShape()
     */
    public abstract int[] getShape();

    /**
     * Returns the names of all attributes associated to this variable.
     *
     * @return names of all attributes associated to this variable.
     */
    public abstract Collection<String> getAttributeNames();

    /**
     * Returns the numeric type of the attribute of the given name, or {@code null}
     * if the given attribute is not found or its value is not numeric.
     *
     * @param  attributeName  the name of the attribute for which to get the type.
     * @return type of the given attribute, or {@code null} if none or not numeric.
     *
     * @see #getDataType()
     */
    public abstract Class<? extends Number> getAttributeType(String attributeName);

    /**
     * Returns the sequence of values for the given attribute, or an empty array if none.
     * The elements will be of class {@link String} if {@code numeric} is {@code false},
     * or {@link Number} if {@code numeric} is {@code true}. Some elements may be null
     * if they are not of the expected type.
     *
     * @param  attributeName  the name of the attribute for which to get the values.
     * @param  numeric        {@code true} if the values are expected to be numeric, or {@code false} for strings.
     * @return the sequence of {@link String} or {@link Number} values for the named attribute.
     *         May contain null elements.
     */
    public abstract Object[] getAttributeValues(String attributeName, boolean numeric);

    /**
     * Returns the singleton value for the given attribute, or {@code null} if none or ambiguous.
     *
     * @param  attributeName  the name of the attribute for which to get the value.
     * @param  numeric        {@code true} if the value is expected to be numeric, or {@code false} for string.
     * @return the {@link String} or {@link Number} value for the named attribute.
     */
    public final Object getAttributeValue(final String attributeName, final boolean numeric) {
        Object singleton = null;
        for (final Object value : getAttributeValues(attributeName, numeric)) {
            if (value != null) {
                if (singleton != null && !singleton.equals(value)) {              // Paranoiac check.
                    return null;
                }
                singleton = value;
            }
        }
        return singleton;
    }

    /**
     * Returns the value of the given attribute as a string. This is a convenience method
     * for {@link #getAttributeValues(String, boolean)} when a singleton value is expected.
     *
     * @param  attributeName  the name of the attribute for which to get the value.
     * @return the singleton attribute value, or {@code null} if none or ambiguous.
     */
    public String getAttributeAsString(final String attributeName) {
        final Object value = getAttributeValue(attributeName, false);
        return (value != null) ? value.toString() : null;
    }

    /**
     * Returns the value of the given attribute as a number, or {@link Double#NaN}.
     * If the number is stored with single-precision, it is assumed casted from a
     * representation in base 10.
     *
     * @param  attributeName  the name of the attribute for which to get the value.
     * @return the singleton attribute value, or {@code NaN} if none or ambiguous.
     */
    public final double getAttributeAsNumber(final String attributeName) {
        final Object value = getAttributeValue(attributeName, true);
        if (value instanceof Number) {
            double dp = ((Number) value).doubleValue();
            final float sp = (float) dp;
            if (sp == dp) {                              // May happen even if the number was stored as a double.
                dp = DecimalFunctions.floatToDouble(sp);
            }
            return dp;
        }
        return Double.NaN;
    }

    /**
     * Returns the range of valid values, or {@code null} if unknown.
     * The range of values is taken from the following properties, in precedence order:
     *
     * <ol>
     *   <li>{@code "valid_range"}  — expected "reasonable" range for variable.</li>
     *   <li>{@code "actual_range"} — actual data range for variable.</li>
     *   <li>{@code "valid_min"}    — ignored if {@code "valid_range"} is present, as specified in UCAR documentation.</li>
     *   <li>{@code "valid_max"}    — idem.</li>
     * </ol>
     *
     * @return the range of valid values, or {@code null} if unknown.
     */
    public NumberRange<?> getValidValues() {
        Number minimum = null;
        Number maximum = null;
        Class<? extends Number> type = null;
        for (final String attribute : RANGE_ATTRIBUTES) {
            for (final Object element : getAttributeValues(attribute, true)) {
                if (element instanceof Number) {
                    Number value = (Number) element;
                    if (element instanceof Float) {
                        final float fp = (Float) element;
                        if      (fp == +Float.MAX_VALUE) value = Float.POSITIVE_INFINITY;
                        else if (fp == -Float.MAX_VALUE) value = Float.NEGATIVE_INFINITY;
                    } else if (element instanceof Double) {
                        final double fp = (Double) element;
                        if      (fp == +Double.MAX_VALUE) value = Double.POSITIVE_INFINITY;
                        else if (fp == -Double.MAX_VALUE) value = Double.NEGATIVE_INFINITY;
                    }
                    type = Numbers.widestClass(type, value.getClass());
                    minimum = Numbers.cast(minimum, type);
                    maximum = Numbers.cast(maximum, type);
                    value   = Numbers.cast(value,   type);
                    if (!attribute.endsWith("max") && (minimum == null || compare(value, minimum) < 0)) minimum = value;
                    if (!attribute.endsWith("min") && (maximum == null || compare(value, maximum) > 0)) maximum = value;
                }
            }
            if (minimum != null && maximum != null) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                final NumberRange<?> range = new NumberRange(type, minimum, true, maximum, true);
                return range;
            }
        }
        /*
         * If we found no explicit range attribute and if the variable type is an integer type,
         * then infer the range from the variable type and exclude the ranges of nodata values.
         */
        final DataType dataType = getDataType();
        if (dataType.isInteger) {
            final int size = dataType.size() * Byte.SIZE;
            if (size > 0 && size <= Long.SIZE) {
                long min = 0;
                long max = (1L << size) - 1;
                if (!dataType.isUnsigned) {
                    max >>>= 1;
                    min = ~max;
                }
                for (final Number value : getNodataValues().keySet()) {
                    final long n = value.longValue();
                    final long Δmin = (n - min);            // Should be okay even with long unsigned values.
                    final long Δmax = (max - n);
                    if (Δmin >= 0 && Δmax >= 0) {           // Test if the pad/missing value is inside range.
                        if (Δmin < Δmax) min = n + 1;       // Reduce the extremum closest to the pad value.
                        else             max = n - 1;
                    }
                }
                if (max > min) {        // Note: this will also exclude unsigned long if max > Long.MAX_VALUE.
                    if (min >= Integer.MIN_VALUE && max <= Integer.MAX_VALUE) {
                        return NumberRange.create((int) min, true, (int) max, true);
                    }
                    return NumberRange.create(min, true, max, true);
                }
            }
        }
        return null;
    }

    /**
     * Returns all no-data values declared for this variable, or an empty map if none.
     * The map keys are pad sample values or missing sample values. The map values are
     * bitmask identifying the role of the pad/missing sample value:
     *
     * <ul>
     *   <li>If bit 0 is set, then the value is a pad value. Those values can be used for background.</li>
     *   <li>If bit 1 is set, then the value is a missing value.</li>
     * </ul>
     *
     * Pad values are first in the map, followed by missing values.
     * The same value may have more than one role.
     * The map returned by this method shall be stable, i.e. two invocations of this method shall return the
     * same entries in the same order. This is necessary for mapping "no data" values to the same NaN values,
     * since their {@linkplain MathFunctions#toNanFloat(int) ordinal values} are based on order.
     *
     * @return pad/missing values with bitmask of their role.
     */
    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public final Map<Number,Integer> getNodataValues() {
        if (nodataValues == null) {
            final Map<Number,Integer> pads = new LinkedHashMap<>();
            for (int i=0; i < NODATA_ATTRIBUTES.length; i++) {
                for (final Object value : getAttributeValues(NODATA_ATTRIBUTES[i], true)) {
                    if (value instanceof Number) {
                        pads.merge((Number) value, 1 << i, (v1, v2) -> v1 | v2);
                    }
                }
            }
            nodataValues = CollectionsExt.unmodifiableOrCopy(pads);
        }
        return nodataValues;
    }

    /**
     * Compares two numbers which shall be of the same class.
     */
    @SuppressWarnings("unchecked")
    private static int compare(final Number n1, final Number n2) {
        return ((Comparable) n1).compareTo((Comparable) n2);
    }

    /**
     * Whether {@link #read()} invoked {@link Vector#compress(double)} on the returned vector.
     * This information is used for avoiding to do twice some potentially costly operations.
     *
     * @return whether {@link #read()} invokes {@link Vector#compress(double)}.
     */
    protected abstract boolean readTriesToCompress();

    /**
     * Reads all the data for this variable and returns them as an array of a Java primitive type.
     * Multi-dimensional variables are flattened as a one-dimensional array (wrapped in a vector).
     * Example:
     *
     * {@preformat text
     *   DIMENSIONS:
     *     time: 3
     *     lat : 2
     *     lon : 4
     *
     *   VARIABLES:
     *     temperature (time,lat,lon)
     *
     *   DATA INDICES:
     *     (0,0,0) (0,0,1) (0,0,2) (0,0,3)
     *     (0,1,0) (0,1,1) (0,1,2) (0,1,3)
     *     (1,0,0) (1,0,1) (1,0,2) (1,0,3)
     *     (1,1,0) (1,1,1) (1,1,2) (1,1,3)
     *     (2,0,0) (2,0,1) (2,0,2) (2,0,3)
     *     (2,1,0) (2,1,1) (2,1,2) (2,1,3)
     * }
     *
     * If {@link #hasRealValues()} returns {@code true}, then this method shall
     * {@linkplain #replaceNaN(Object) replace fill values and missing values by NaN values}.
     * This method should cache the returned vector since this method may be invoked often.
     * Because of caching, this method should not be invoked for large data array.
     * Callers shall not modify the returned vector.
     *
     * @return the data as an array of a Java primitive type.
     * @throws IOException if an error occurred while reading the data.
     * @throws DataStoreException if a logical error occurred.
     * @throws ArithmeticException if the size of the variable exceeds {@link Integer#MAX_VALUE}, or other overflow occurs.
     */
    public abstract Vector read() throws IOException, DataStoreException;

    /**
     * Reads a sub-sampled sub-area of the variable.
     * Constraints on the argument values are:
     *
     * <ul>
     *   <li>Argument dimensions shall be equal to the length of the {@link #getShape()} array.</li>
     *   <li>For each index <var>i</var>, value of {@code area[i]} shall be in the range from 0 inclusive
     *       to {@code Integer.toUnsignedLong(getShape()[length - 1 - i])} exclusive.</li>
     *   <li>Values are in "natural" order (inverse of netCDF order).</li>
     * </ul>
     *
     * If the variable has more than one dimension, then the data are packed in a one-dimensional vector
     * in the same way than {@link #read()}. If {@link #hasRealValues()} returns {@code true}, then this
     * method shall {@linkplain #replaceNaN(Object) replace fill/missing values by NaN values}.
     *
     * @param  area         indices of cell values to read along each dimension, in "natural" order.
     * @param  subsampling  sub-sampling along each dimension. 1 means no sub-sampling.
     * @return the data as an array of a Java primitive type.
     * @throws IOException if an error occurred while reading the data.
     * @throws DataStoreException if a logical error occurred.
     * @throws ArithmeticException if the size of the region to read exceeds {@link Integer#MAX_VALUE}, or other overflow occurs.
     */
    public abstract Vector read(GridExtent area, int[] subsampling) throws IOException, DataStoreException;

    /**
     * Wraps the given data in a {@link Vector} with the assumption that accuracy in base 10 matters.
     * This method is suitable for coordinate axis variables, but should not be used for the main data.
     *
     * @param  data        the data to wrap in a vector.
     * @param  isUnsigned  whether the data type is an unsigned type.
     * @return vector wrapping the given data.
     */
    protected static Vector createDecimalVector(final Object data, final boolean isUnsigned) {
        if (data instanceof float[]) {
            return Vector.createForDecimal((float[]) data);
        } else {
            return Vector.create(data, isUnsigned);
        }
    }

    /**
     * Maybe replace fill values and missing values by {@code NaN} values in the given array.
     * This method does nothing if {@link #hasRealValues()} returns {@code false}.
     * The NaN values used by this method must be consistent with the NaN values declared in
     * the sample dimensions created by {@link org.apache.sis.storage.netcdf.GridResource}.
     *
     * @param  array  the array in which to replace fill and missing values.
     */
    protected final void replaceNaN(final Object array) {
        if (hasRealValues()) {
            int ordinal = 0;
            for (final Number value : getNodataValues().keySet()) {
                final float pad = MathFunctions.toNanFloat(ordinal++);      // Must be consistent with GridResource.createSampleDimension(…).
                if (array instanceof float[]) {
                    ArraysExt.replace((float[]) array, value.floatValue(), pad);
                } else if (array instanceof double[]) {
                    ArraysExt.replace((double[]) array, value.doubleValue(), pad);
                }
            }
        }
    }

    /**
     * Sets the scale and offset coefficients in the given "grid to CRS" transform if possible.
     * Source and target dimensions given to this method are in "natural" order (reverse of netCDF order).
     * This method is invoked only for variables that represent a coordinate system axis.
     * Setting the coefficient is possible only if values in this variable are regular,
     * i.e. the difference between two consecutive values is constant.
     *
     * @param  gridToCRS  the matrix in which to set scale and offset coefficient.
     * @param  srcDim     the source dimension, which is a dimension of the grid. Identifies the matrix column of scale factor.
     * @param  tgtDim     the target dimension, which is a dimension of the CRS.  Identifies the matrix row of scale factor.
     * @param  values     the vector to use for computing scale and offset, or {@code null} if it has not been read yet.
     * @return whether this method successfully set the scale and offset coefficients.
     * @throws IOException if an error occurred while reading the data.
     * @throws DataStoreException if a logical error occurred.
     */
    protected boolean trySetTransform(final Matrix gridToCRS, final int srcDim, final int tgtDim, Vector values)
            throws IOException, DataStoreException
    {
        if (values == null) {
            values = read();
        }
        final int n = values.size() - 1;
        if (n >= 0) {
            final double first = values.doubleValue(0);
            Number increment;
            if (n >= 1) {
                final double last = values.doubleValue(n);
                double error;
                if (getDataType() == DataType.FLOAT) {
                    error = Math.max(Math.ulp((float) first), Math.ulp((float) last));
                } else {
                    error = Math.max(Math.ulp(first), Math.ulp(last));
                }
                error = Math.max(Math.ulp(last - first), error) / n;
                increment = values.increment(error);
            } else {
                increment = Double.NaN;
            }
            if (increment != null) {
                gridToCRS.setElement(tgtDim, srcDim, increment.doubleValue());
                gridToCRS.setElement(tgtDim, gridToCRS.getNumCol() - 1, first);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the locale to use for warnings and error messages.
     */
    final Locale getLocale() {
        return listeners.getLocale();
    }

    /**
     * Returns the resources to use for warnings or error messages.
     *
     * @return the resources for the locales specified to the decoder.
     */
    protected final Resources resources() {
        return Resources.forLocale(getLocale());
    }

    /**
     * Reports a warning to the listeners specified at construction time.
     *
     * @param  caller     the caller class to report, preferably a public class.
     * @param  method     the caller method to report, preferable a public method.
     * @param  key        one or {@link Resources.Keys} constants.
     * @param  arguments  values to be formatted in the {@link java.text.MessageFormat} pattern.
     */
    protected final void warning(final Class<?> caller, final String method, final short key, final Object... arguments) {
        warning(listeners, caller, method, null, null, key, arguments);
    }

    /**
     * Reports a warning to the listeners specified at construction time.
     *
     * @param  caller     the caller class to report, preferably a public class.
     * @param  method     the caller method to report, preferable a public method.
     * @param  exception  the exception that occurred, or {@code null} if none.
     * @param  key        one or {@link Errors.Keys} constants.
     * @param  arguments  values to be formatted in the {@link java.text.MessageFormat} pattern.
     */
    protected final void error(final Class<?> caller, final String method, final Exception exception, final short key, final Object... arguments) {
        warning(listeners, caller, method, exception, Errors.getResources(listeners.getLocale()), key, arguments);
    }

    /**
     * Returns a string representation of this variable for debugging purpose.
     *
     * @return a string representation of this variable.
     */
    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder(getName()).append(" : ").append(getDataType());
        final int[] shape = getShape();
        for (int i=shape.length; --i>=0;) {
            buffer.append('[').append(Integer.toUnsignedLong(shape[i])).append(']');
        }
        if (isUnlimited()) {
            buffer.append(" (unlimited)");
        }
        return buffer.toString();
    }
}
