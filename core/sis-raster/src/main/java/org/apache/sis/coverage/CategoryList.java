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
package org.apache.sis.coverage;

import java.util.Arrays;
import java.util.AbstractList;
import java.io.Serializable;
import java.io.ObjectStreamException;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.operation.Matrix;
import org.opengis.referencing.operation.MathTransform1D;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.referencing.operation.matrix.Matrix1;
import org.apache.sis.io.wkt.UnformattableObjectException;
import org.apache.sis.geometry.GeneralDirectPosition;
import org.apache.sis.internal.raster.Resources;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.ArraysExt;
import org.apache.sis.measure.NumberRange;

import static java.lang.Double.isNaN;
import static java.lang.Double.doubleToRawLongBits;


/**
 * An immutable list of categories. Categories are sorted by their sample values.
 * Overlapping ranges of sample values are not allowed. A {@code CategoryList} can contains a mix
 * of qualitative and quantitative categories.  The {@link #search(double)} method is responsible
 * for finding the right category for an arbitrary sample value.
 *
 * <p>Instances of {@link CategoryList} are immutable and thread-safe.</p>
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @version 1.0
 * @since   1.0
 * @module
 */
final class CategoryList extends AbstractList<Category> implements MathTransform1D, Serializable {
    /**
     * Serial number for inter-operability with different versions.
     */
    private static final long serialVersionUID = 2647846361059903365L;

    /**
     * An empty list of categories.
     */
    static final CategoryList EMPTY = new CategoryList();

    /**
     * The union of the ranges of every categories, excluding {@code NaN} values.
     * May be {@code null} if this list has no non-{@code NaN} category.
     *
     * <p>A {@link NumberRange} object gives more information than a (minimum, maximum) tuple since
     * it contains also the type (integer, float, etc.) and inclusion/exclusion information.</p>
     */
    final NumberRange<?> range;

    /**
     * List of {@link Category#minimum} values for each category in {@link #categories}.
     * This array <strong>must</strong> be in increasing order. Actually, this is the
     * need to sort this array that determines the element order in {@link #categories}.
     */
    private final double[] minimums;

    /**
     * The list of categories to use for decoding samples. This list must be sorted in increasing
     * order of {@link Category#minimum}. Qualitative categories with NaN values are last.
     */
    private final Category[] categories;

    /**
     * The category to use if {@link #search(double)} is invoked with a sample value greater than all ranges in this list.
     * This is usually a reference to the last category to have a range of real values. A {@code null} value means that no
     * extrapolation should be used. By extension, a {@code null} value also means that {@link #search(double)} should not
     * try to find any fallback at all if the requested sample value does not fall in a category range.
     *
     * <p>There is no explicit extrapolation field for values less than all ranges in this list because the extrapolation
     * to use in such case is {@code categories[0]}.</p>
     */
    private final Category extrapolation;

    /**
     * The last used category. We assume that this category is the most likely to be requested in the next
     * {@code transform(…)} method invocation.
     *
     * <p>This field is not declared {@code volatile} because we will never assign newly created objects to it.
     * It will always be a reference to an existing category, and it does not matter if referenced category is
     * not really the last used one.</p>
     */
    private transient Category last;

    /**
     * The {@code CategoryList} that describes values after {@linkplain #getTransferFunction() transfer function}
     * has been applied, or if this {@code CategoryList} is already converted then the original {@code CategoryList}.
     * Never null, but may be {@code this} if the transfer function is the identity function.
     * May also be {@link #EMPTY} if this category list has no quantitative category.
     *
     * <p>Exempt for the {@link #EMPTY} special case, this field establishes a bidirectional navigation between
     * sample values and real values. This is in contrast with methods named {@code converted()}, which establish
     * a unidirectional navigation from sample values to real values.</p>
     *
     * @see Category#converse
     * @see SampleDimension#converse
     */
    final CategoryList converse;

    /**
     * The constructor for the {@link #EMPTY} constant.
     */
    private CategoryList() {
        range         = null;
        minimums      = ArraysExt.EMPTY_DOUBLE;
        categories    = new Category[0];
        extrapolation = null;
        converse      = this;
    }

    /**
     * Constructs a category list using the specified array of categories.
     * The {@code categories} array should contain at least one element.
     *
     * @param  categories  the list of categories. May be empty, but can not be null.
     *                     This array is not cloned and is modified in-place.
     * @param  converse    if we are creating the list of categories after conversion from samples to real values,
     *                     the original list before conversion. Otherwise {@code null}.
     * @throws IllegalArgumentException if two or more categories have overlapping sample value range.
     */
    CategoryList(final Category[] categories, CategoryList converse) {
        /*
         * If users specify Category instances themselves, maybe they took existing instances from another
         * sample dimension. A list of "non-converted" categories should not contain any ConvertedCategory
         * instances, otherwise confusion will occur later.  Note that the converse is not true: a list of
         * converted categories may contain plain Category instances if the conversion is identity.
         */
        if (converse == null) {
            for (int i=0; i<categories.length; i++) {
                final Category c = categories[i];
                if (c instanceof ConvertedCategory) {
                    categories[i] = new Category(c);
                }
            }
        }
        Arrays.sort(categories, Category.COMPARATOR);
        this.categories = categories;
        /*
         * Constructs the array of Category.minimum values. During the loop, we make sure there is no overlapping ranges.
         */
        NumberRange<?> range = null;
        minimums = new double[categories.length];
        for (int i=categories.length; --i >= 0;) {
            final Category category = categories[i];
            minimums[i] = category.minimum;
            if (i != 0) {
                final Category previous = categories[i-1];
                if (Category.compare(category.minimum, previous.maximum) <= 0) {
                    throw new IllegalArgumentException(Resources.format(Resources.Keys.CategoryRangeOverlap_4, new Object[] {
                                previous.name, previous.getRangeLabel(),
                                category.name, category.getRangeLabel()}));
                }
            }
            final NumberRange<?> extent = category.range;
            if (extent != null) {
                /*
                 * Initialize with the union of ranges at index 0 and index i.  In most cases, it will cover the whole range
                 * so all future calls to 'range.unionAny(extent)' will be no-op. The 'categories[0].range' field should not
                 * be null because categories with null ranges are sorted last (because their 'minimum' field is NaN).
                 */
                if (range == null) {
                    range = categories[0].range;
                }
                range = range.unionAny(extent);
            }
        }
        this.range = range;
        /*
         * At this point we have two branches:
         *
         *   - If we are creating the list of "samples to real values" conversions, then we do not allow extrapolations
         *     outside the ranges or categories given to this constructor (extrapolation = null). In addition we need
         *     to create the list of categories after conversion to real value.
         *
         *   - If we are creating the list of "real values to samples" conversions, then we need to search for the
         *     extrapolation to use when 'search(double)' is invoked with a value greater than all ranges in this
         *     list. This is the last category to have a range of real (non-NaN) numbers.
         */
        Category extrapolation = null;
        if (converse == null) {
            boolean hasConversion   = false;
            boolean hasQuantitative = false;
            final Category[] convertedCategories = new Category[categories.length];
            for (int i=0; i < convertedCategories.length; i++) {
                final Category category = categories[i];
                hasConversion   |= (category != category.converse);
                hasQuantitative |= (category.converse.range != null);
                convertedCategories[i] = category.converse;
            }
            if (hasQuantitative) {
                converse = hasConversion ? new CategoryList(convertedCategories, this) : this;
            } else {
                converse = EMPTY;
            }
        } else {
            for (int i=categories.length; --i >= 0;) {
                final Category category = categories[i];
                if (!isNaN(category.maximum)) {
                    extrapolation = category;
                    break;
                }
            }
        }
        this.extrapolation = extrapolation;
        this.converse      = converse;
        if (categories.length != 0) {
            last = categories[0];
        }
    }

    /**
     * Computes transient fields and potentially returns a shared instance.
     *
     * @return the object to use after deserialization.
     * @throws ObjectStreamException if the serialized object contains invalid data.
     */
    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    private Object readResolve() throws ObjectStreamException {
        if (categories.length == 0) {
            return EMPTY;
        } else {
            last = categories[0];
            return this;
        }
    }

    /**
     * Returns the <cite>transfer function</cite> from sample values to real values, including conversion
     * of "no data" values to NaNs. Callers shall ensure that there is at least one quantitative category
     * before to invoke this method.
     *
     * @see SampleDimension#getTransferFunction()
     */
    final MathTransform1D getTransferFunction() {
        MathTransform1D tr = categories[0].toConverse;          // See condition in javadoc.
        for (int i=categories.length; --i >= 1;) {
            if (!tr.equals(categories[i].toConverse)) {
                tr = this;
                break;
            }
        }
        return tr;
    }

    /**
     * Performs a bi-linear search of the specified value. This method is similar to
     * {@link Arrays#binarySearch(double[],double)} except that it can differentiate
     * the various NaN values.
     */
    static int binarySearch(final double[] array, final double key) {
        int low  = 0;
        int high = array.length - 1;
        final boolean keyIsNaN = isNaN(key);
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final double midVal = array[mid];
            if (midVal < key) {                         // Neither value is NaN, midVal is smaller.
                low = mid + 1;
                continue;
            }
            if (midVal > key) {                         // Neither value is NaN, midVal is larger.
                high = mid - 1;
                continue;
            }
            final long midRawBits = doubleToRawLongBits(midVal);
            final long keyRawBits = doubleToRawLongBits(key);
            if (midRawBits == keyRawBits) {
                return mid;                             // Key found.
            }
            final boolean midIsNaN = isNaN(midVal);
            final boolean adjustLow;
            if (keyIsNaN) {
                /*
                 * If (mid,key)==(!NaN, NaN): mid is lower.
                 * If two NaN arguments, compare NaN bits.
                 */
                adjustLow = (!midIsNaN || midRawBits < keyRawBits);
            } else {
                /*
                 * If (mid,key)==(NaN, !NaN): mid is greater.
                 * Otherwise, case for (-0.0, 0.0) and (0.0, -0.0).
                 */
                adjustLow = (!midIsNaN && midRawBits < keyRawBits);
            }
            if (adjustLow) low = mid + 1;
            else          high = mid - 1;
        }
        return ~low;                                    // key not found.
    }

    /**
     * Returns the category of the specified sample value.
     * If no category fits, then this method returns {@code null}.
     *
     * @param  sample  the value.
     * @return the category of the supplied value, or {@code null}.
     */
    final Category search(final double sample) {
        /*
         * Search which category contains the given value.
         * Note: NaN values are at the end of 'minimums' array, so:
         *
         * 1) if 'value' is NaN, then 'i' will be the index of a NaN category.
         * 2) if 'value' is a real number, then 'i' may be the index of a category
         *    of real numbers or the first category containing NaN values.
         */
        int i = binarySearch(minimums, sample);                             // Special 'binarySearch' for NaN
        if (i >= 0) {
            assert doubleToRawLongBits(sample) == doubleToRawLongBits(minimums[i]);
            return categories[i];
        }
        /*
         * If we reach this point and the value is NaN, then it is not one of the NaN values known
         * to CategoryList constructor. Consequently we can not map a category to this value.
         */
        if (isNaN(sample)) {
            return null;
        }
        assert i == Arrays.binarySearch(minimums, sample) : i;
        /*
         * 'binarySearch' found the index of "insertion point" (~i). This means that 'sample' is lower
         * than 'Category.minimum' at that index. Consequently if the sample value is inside the range
         * of some category, it can only be the previous category (~i-1).
         */
        i = ~i - 1;
        if (i >= 0) {
            final Category category = categories[i];
            assert sample > category.minimum : sample;
            if (sample <= category.maximum) {
                return category;
            }
            /*
             * At this point we determined that 'sample' is between two categories. If extrapolations
             * are allowed, returns the category for the range closest to the sample value.
             *
             * Assertion: 'next.minimum' shall not be smaller than 'sample', otherwise it should have
             * been found by 'binarySearch'.
             */
            if (extrapolation != null) {
                if (++i < categories.length) {
                    final Category next = categories[i];
                    assert !(next.minimum <= sample) : sample;         // '!' for accepting NaN.
                    return (next.minimum - sample < sample - category.maximum) ? next : category;
                }
                return extrapolation;
            }
        } else if (extrapolation != null) {
            /*
             * If the value is smaller than the smallest Category.minimum, returns
             * the first category (except if there is only qualitative categories).
             */
            if (categories.length != 0) {
                final Category category = categories[0];
                if (!isNaN(category.minimum)) {
                    return category;
                }
            }
        }
        return null;
    }

    /**
     * Transforms a list of coordinate point ordinal values. This implementation accepts
     * float or double arrays, since the quasi-totality of the implementation is the same.
     * Locale variables still of the {@code double} type because this is the type used in
     * {@link Category} objects.
     */
    private void transform(final double[] srcPts, final float[] srcFloat, int srcOff,
                           final double[] dstPts, final float[] dstFloat, int dstOff,
                           int numPts) throws TransformException
    {
        final int srcToDst = dstOff - srcOff;
        final int direction;
        if (srcOff >= dstOff || (srcFloat != null ? srcFloat != dstFloat : srcPts != dstPts)) {
            direction = +1;
        } else {
            direction = -1;
//          dstOff += numPts-1;             // Not updated because not used.
            srcOff += numPts-1;
        }
        /*
         * Scan every points.  Transforms will be applied by blocks, each time the loop detects that
         * the category has changed. The break condition (numPts >= 0) is near the end of the loop,
         * after we have done the conversion but before to change category.
         */
        Category category = last;
        double value = Double.NaN;
        for (int peekOff = srcOff; /* numPts >= 0 */; peekOff += direction) {
            final double minimum = category.minimum;
            final double maximum = category.maximum;
            final long   rawBits = doubleToRawLongBits(minimum);
            while (--numPts >= 0) {
                value = (srcFloat != null) ? srcFloat[peekOff] : srcPts[peekOff];
                if (value >= minimum) {
                    if (!(value <= maximum || category == extrapolation)) {
                        /*
                         * If the value is greater than the [minimum … maximum] range and extrapolation
                         * is not allowed, then consider that the category has changed; stop the search.
                         */
                        break;
                    }
                } else if (doubleToRawLongBits(value) != rawBits &&
                        (isNaN(value) || extrapolation == null || category != categories[0]))
                {
                    /*
                     * If the value is not the expected NaN value, or the value is a real number less than
                     * the [minimum … maximum] range with extrapolation not allowed, then consider that the
                     * category has changed; stop the search.
                     */
                    break;
                }
                peekOff += direction;
            }
            /*
             * The category has changed. Compute the start point (which depends on 'direction') and perform
             * the conversion. If 'search' was allowed to search for the nearest category, clamp all output
             * values in their category range.
             */
            int count = peekOff - srcOff;                       // May be negative if we are going backward.
            if (count < 0) {
                count  = -count;
                srcOff -= count - 1;
            }
            final int stepOff = srcOff + srcToDst;
            final MathTransform1D piece = category.toConverse;
            if (srcFloat != null) {
                if (dstFloat != null) {
                    piece.transform(srcFloat, srcOff, dstFloat, stepOff, count);
                } else {
                    piece.transform(srcFloat, srcOff, dstPts, stepOff, count);
                }
            } else {
                if (dstFloat != null) {
                    piece.transform(srcPts, srcOff, dstFloat, stepOff, count);
                } else {
                    piece.transform(srcPts, srcOff, dstPts, stepOff, count);
                }
            }
            if (extrapolation != null) {
                dstOff = srcOff + srcToDst;
                final Category converse = category.converse;
                if (dstFloat != null) {                                 // Loop for the 'float' version.
                    final float min = (float) converse.minimum;
                    final float max = (float) converse.maximum;
                    while (--count >= 0) {
                        final float check = dstFloat[dstOff];
                        if (check < min) {
                            dstFloat[dstOff] = min;
                        } else if (check > max) {
                            dstFloat[dstOff] = max;
                        }
                        dstOff++;
                    }
                } else {                                                // Loop for the 'double' version.
                    final double min = converse.minimum;
                    final double max = converse.maximum;
                    while (--count >= 0) {
                        final double check = dstPts[dstOff];
                        if (check < min) {
                            dstPts[dstOff] = min;
                        } else if (check > max) {
                            dstPts[dstOff] = max;
                        }
                        dstOff++;
                    }
                }
            }
            /*
             * Transformation is now finished for all points in the range [srcOff … peekOff]
             * (not including 'peekOff'). If there is more points to examine, get the new
             * category for the next points.
             */
            if (numPts < 0) break;
            category = search(value);
            if (category == null) {
                throw new TransformException(Resources.format(Resources.Keys.NoCategoryForValue_1, value));
            }
            srcOff = peekOff;
        }
        last = category;
    }

    /**
     * Transforms a list of coordinate point ordinal values.
     */
    @Override
    public final void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) throws TransformException {
        transform(srcPts, null, srcOff, dstPts, null, dstOff, numPts);
    }

    /**
     * Transforms a list of coordinate point ordinal values.
     */
    @Override
    public final void transform(float[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) throws TransformException {
        transform(null, srcPts, srcOff, null, dstPts, dstOff, numPts);
    }

    /**
     * Transforms a list of coordinate point ordinal values.
     */
    @Override
    public final void transform(float[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) throws TransformException {
        transform(null, srcPts, srcOff, dstPts, null, dstOff, numPts);
    }

    /**
     * Transforms a list of coordinate point ordinal values.
     */
    @Override
    public final void transform(double[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) throws TransformException {
        transform(srcPts, null, srcOff, null, dstPts, dstOff, numPts);
    }

    /**
     * Transforms the specified value.
     *
     * @param  value  the value to transform.
     * @return the transformed value.
     * @throws TransformException if the value can not be transformed.
     */
    @Override
    public final double transform(double value) throws TransformException {
        Category category = last;
        if (!(value >= category.minimum  &&  value <= category.maximum) &&
             doubleToRawLongBits(value) != doubleToRawLongBits(category.minimum))
        {
            category = search(value);
            if (category == null) {
                throw new TransformException(Resources.format(Resources.Keys.NoCategoryForValue_1, value));
            }
            last = category;
        }
        value = category.toConverse.transform(value);
        if (extrapolation != null) {
            double bound;
            if (value < (bound = category.converse.minimum)) return bound;
            if (value > (bound = category.converse.maximum)) return bound;
        }
        assert category == converse.search(value).converse : category;
        return value;
    }

    /**
     * Gets the derivative of this function at a value.
     *
     * @param  value  the value where to evaluate the derivative.
     * @return the derivative at the specified point.
     * @throws TransformException if the derivative can not be evaluated at the specified point.
     */
    @Override
    public final double derivative(final double value) throws TransformException {
        Category category = last;
        if (!(value >= category.minimum  &&  value <= category.maximum) &&
             doubleToRawLongBits(value) != doubleToRawLongBits(category.minimum))
        {
            category = search(value);
            if (category == null) {
                throw new TransformException(Resources.format(Resources.Keys.NoCategoryForValue_1, value));
            }
            last = category;
        }
        return category.toConverse.derivative(value);
    }

    /**
     * Transforms the specified {@code ptSrc} and stores the result in {@code ptDst}.
     */
    @Override
    public final DirectPosition transform(final DirectPosition ptSrc, DirectPosition ptDst) throws TransformException {
        ArgumentChecks.ensureNonNull("ptSrc", ptSrc);
        ArgumentChecks.ensureDimensionMatches("ptSrc", 1, ptSrc);
        if (ptDst == null) {
            ptDst = new GeneralDirectPosition(1);
        } else {
            ArgumentChecks.ensureDimensionMatches("ptDst", 1, ptDst);
        }
        ptDst.setOrdinate(0, transform(ptSrc.getOrdinate(0)));
        return ptDst;
    }

    /**
     * Gets the derivative of this transform at a point.
     */
    @Override
    public final Matrix derivative(final DirectPosition point) throws TransformException {
        ArgumentChecks.ensureNonNull("point", point);
        ArgumentChecks.ensureDimensionMatches("point", 1, point);
        return new Matrix1(derivative(point.getOrdinate(0)));
    }

    /**
     * Tests whether this transform does not move any points.
     */
    @Override
    public boolean isIdentity() {
        return converse == this;
    }

    /**
     * Returns the inverse transform of this object, which may be {@code this} if this transform is identity.
     */
    @Override
    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public final MathTransform1D inverse() {
        return converse;
    }

    /**
     * Gets the dimension of input points, which is 1.
     */
    @Override
    public final int getSourceDimensions() {
        return 1;
    }

    /**
     * Gets the dimension of output points, which is 1.
     */
    @Override
    public final int getTargetDimensions() {
        return 1;
    }

    /**
     * Returns the number of categories in this list.
     */
    @Override
    public final int size() {
        return categories.length;
    }

    /**
     * Returns the element at the specified position in this list.
     */
    @Override
    public final Category get(final int i) {
        return categories[i];
    }

    /**
     * Compares the specified object with this category list for equality.
     */
    @Override
    public boolean equals(final Object object) {
        if (object instanceof CategoryList) {
            final CategoryList that = (CategoryList) object;
            if (Arrays.equals(categories, that.categories)) {
                assert Arrays.equals(minimums, that.minimums);
            } else {
                return false;
            }
        }
        return super.equals(object);
    }

    /**
     * Returns a <cite>Well Known Text</cite> (WKT) for this object. This operation
     * may fail if an object is too complex for the WKT format capability.
     *
     * @return the Well Know Text for this object.
     * @throws UnsupportedOperationException if this object can not be formatted as WKT.
     *
     * @todo Not yet implemented.
     */
    @Override
    public String toWKT() throws UnsupportedOperationException {
        throw new UnformattableObjectException("Not yet implemented.");
    }
}
