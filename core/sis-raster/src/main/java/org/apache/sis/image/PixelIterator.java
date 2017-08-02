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
package org.apache.sis.image;

import java.awt.Point;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.util.NoSuchElementException;
import org.opengis.coverage.grid.SequenceType;
import org.apache.sis.util.ArgumentChecks;

import static java.lang.Math.floorDiv;


/**
 * An iterator over sample values in a raster or an image.  This iterator makes easier to read and write efficiently
 * pixel or sample values. The iterator {@linkplain RenderedImage#getTile(int,int) acquires tiles} and releases them
 * automatically. Unless otherwise specified, iterators are free to use an {@linkplain #getIterationOrder() iteration
 * order} that minimize the "acquire / release tile" operations (in other words, iterations are not necessarily from
 * left to right). Iteration can be performed on a complete image or only a sub-region of it. Some optimized iterator
 * implementations exist for a few commonly used {@linkplain java.awt.image.SampleModel sample models}.
 *
 * <p>Usage example:</p>
 * {@preformat java
 *     PixelIterator it = PixelIterator.create(image, null);
 *     double[] samples = null;
 *     while (it.next()) {
 *         samples = it.getPixel(samples);      // Get values in all bands.
 *         // Perform computation here...
 *     }
 * }
 *
 * @author  Rémi Maréchal (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @version 0.8
 * @since   0.8
 * @module
 */
public abstract class PixelIterator {
    /**
     * The image in which iteration is occurring, or {@code null} if none.
     * If {@code null}, then {@link #currentRaster} must be non-null.
     */
    final RenderedImage image;

    /**
     * The current raster in which iteration is occurring. This may change when the iterator
     * reaches a new {@link #image} tile. May be {@code null} if not yet determined.
     */
    Raster currentRaster;

    /**
     * Number of bands in all tiles in the {@linkplain #image}.
     * The {@link #currentRaster} shall always have this number of bands.
     */
    final int numBands;

    /**
     * The domain, in pixel coordinates, of the region traversed by this pixel iterator.
     * This may be smaller than the image or raster bounds, but not greater.
     * The lower values are inclusive and the upper values exclusive.
     *
     * @see #getDomain()
     */
    final int lowerX, lowerY, upperX, upperY;

    /**
     * Size of all tiles in the {@link #image}.
     */
    final int tileWidth, tileHeight;

    /**
     * The X and Y coordinate of the upper-left pixel of tile (0,0).
     * Note that tile (0,0) may not actually exist.
     */
    final int tileGridXOffset, tileGridYOffset;

    /**
     * The domain, in tile coordinates, of the region traversed by this pixel iterator.
     * This may be smaller than the image or raster tile grid bounds, but not greater.
     * The lower values are inclusive and the upper values exclusive.
     */
    final int tileLowerX, tileLowerY, tileUpperX, tileUpperY;

    /**
     * Size of the window to use in {@link #window()} method, or {@code 0} if none.
     */
    final int windowWidth, windowHeight;

    /**
     * Sample values in the window, or {@code null} if not yet extracted.
     * Current sample values. This array is overwritten when {@link #window()} is invoked.
     */
    transient double[] window;

    /**
     * Creates an iterator for the given region in the given raster.
     *
     * @param  data     the raster which contains the sample values on which to iterate.
     * @param  subArea  the raster region where to perform the iteration, or {@code null}
     *                  for iterating over all the raster domain.
     * @param  window   size of the window to use in {@link #window()} method, or {@code null} if none.
     */
    PixelIterator(final Raster data, final Rectangle subArea, final Dimension window) {
        ArgumentChecks.ensureNonNull("data", data);
        final Rectangle bounds;
        image           = null;
        currentRaster   = data;
        numBands        = data.getNumBands();
        tileWidth       = data.getWidth();
        tileHeight      = data.getHeight();
        tileGridXOffset = data.getMinX();
        tileGridYOffset = data.getMinY();
        tileLowerX      = 0;                    // In this case only one raster: tile index is fixed to 0.
        tileLowerY      = 0;
        tileUpperX      = 1;
        tileUpperY      = 1;
        bounds          = intersection(tileGridXOffset, tileGridYOffset, tileWidth, tileHeight, subArea, window);
        lowerX          = bounds.x;
        lowerY          = bounds.y;
        upperX          = Math.addExact(lowerX, bounds.width);
        upperY          = Math.addExact(lowerY, bounds.height);
        windowWidth     = (window != null) ? window.width  : 0;
        windowHeight    = (window != null) ? window.height : 0;
    }

    /**
     * Creates an iterator for the given region in the given image.
     *
     * @param  data     the image which contains the sample values on which to iterate.
     * @param  subArea  the image region where to perform the iteration, or {@code null}
     *                  for iterating over all the image domain.
     * @param  window   size of the window to use in {@link #window()} method, or {@code null} if none.
     */
    PixelIterator(final RenderedImage data, final Rectangle subArea, final Dimension window) {
        ArgumentChecks.ensureNonNull("data", data);
        final Rectangle bounds;
        image           = data;
        numBands        = data.getSampleModel().getNumBands();
        tileWidth       = data.getTileWidth();
        tileHeight      = data.getTileHeight();
        tileGridXOffset = data.getTileGridXOffset();
        tileGridYOffset = data.getTileGridYOffset();
        bounds          = intersection(data.getMinX(), data.getMinY(), data.getWidth(), data.getHeight(), subArea, window);
        lowerX          = bounds.x;
        lowerY          = bounds.y;
        upperX          = Math.addExact(lowerX, bounds.width);
        upperY          = Math.addExact(lowerY, bounds.height);
        tileLowerX      = floorDiv(Math.subtractExact(lowerX, tileGridXOffset), tileWidth);
        tileLowerY      = floorDiv(Math.subtractExact(lowerY, tileGridYOffset), tileHeight);
        tileUpperX      =  ceilDiv(Math.subtractExact(upperX, tileGridXOffset), tileWidth);
        tileUpperY      =  ceilDiv(Math.subtractExact(upperY, tileGridYOffset), tileHeight);
        windowWidth     = (window != null) ? window.width  : 0;
        windowHeight    = (window != null) ? window.height : 0;
    }

    /**
     * Returns {@code numerator / denominator} rounded toward positive infinity.
     */
    private static int ceilDiv(final int numerator, final int denominator) {
        return -floorDiv(-numerator, denominator);
    }

    /**
     * Computes the intersection between the given bounds and and {@code subArea} if {@code subArea} is non-null.
     * If the result is empty, then the width and/or height are set to zero (not negative).
     */
    private static Rectangle intersection(int x, int y, int width, int height, Rectangle subArea, Dimension window) {
        if (window != null) {
            ArgumentChecks.ensureBetween("window.width",  1, width,  window.width);
            ArgumentChecks.ensureBetween("window.height", 1, height, window.height);
            width  -= (window.width  - 1);
            height -= (window.height - 1);
        }
        Rectangle bounds = new Rectangle(x, y, width, height);
        if (subArea != null) {
            bounds = bounds.intersection(subArea);
            if (bounds.width  < 0) bounds.width  = 0;
            if (bounds.height < 0) bounds.height = 0;
        }
        return bounds;
    }

    /**
     * Returns the pixel coordinates of the region where this iterator is doing the iteration.
     * If no region was specified at construction time, then this method returns the image or
     * raster bounds.
     *
     * @return pixel coordinates of the iteration region.
     */
    public Rectangle getDomain() {
        return new Rectangle(lowerX, lowerY, upperX - lowerX, upperY - lowerY);
    }

    /**
     * Returns the order in which pixels are traversed. {@link SequenceType#LINEAR} means that pixels on the first
     * row are traversed from left to right, then pixels on the second row from left to right, <i>etc.</i>
     * A {@code null} value means that the iteration order is unspecified.
     *
     * @return order in which pixels are traversed, or {@code null} if unspecified.
     */
    public abstract SequenceType getIterationOrder();

    /**
     * Returns the column (x) and row (y) indices of the current pixel.
     * The {@link #next()} or {@link #moveTo(int,int)} method must have been invoked before this method.
     * Indices of the first pixel are not necessarily zero; they can even be negative.
     *
     * @return column and row indices of current iterator position.
     * @throws IllegalStateException if this method is invoked before the first call to {@link #next()}
     *         or {@link #moveTo(int,int)}, or after {@code next()} returned {@code false}.
     */
    public abstract Point getPosition();

    /**
     * Moves the pixel iterator to the given column (x) and row (y) indices. After this method invocation,
     * the iterator state is as if the {@link #next()} method has been invoked just before to reach the
     * specified position.
     *
     * <div class="note"><b>Usage example:</b>
     * {@preformat java
     *     iterator.moveTo(x, y);
     *     do {
     *         int sample = iterator.getSample(band);
     *         // Use sample value here...
     *     } while (iterator.next());
     * }
     * </div>
     *
     * @param  x  the column index of the pixel to make current.
     * @param  y  the row index of the pixel to make current.
     * @throws IndexOutOfBoundsException if the given indices are outside the iteration domain.
     */
    public abstract void moveTo(int x, int y);

    /**
     * Moves the iterator to the next pixel. A pixel iterator is initially positioned before the first pixel.
     * The first call to {@code next()} makes the first pixel the current one; the second call makes the second
     * pixel the current one, <i>etc.</i> The second pixel is not necessarily on the same row than the first one;
     * iteration order is implementation dependent.
     *
     * <p>When a call to {@code next()} returns {@code false}, the iterator is positioned after the last pixel.
     * Any invocation of a {@code getSample(int)} method will result in a {@link NoSuchElementException} to be
     * thrown.</p>
     *
     * @return {@code true} if the current pixel is valid, or {@code false} if there is no more pixels.
     * @throws IllegalStateException if this iterator already reached end of iteration in a previous call
     *         to {@code next()}, and {@link #rewind()} or {@link #moveTo(int,int)} have not been invoked.
     */
    public abstract boolean next();

    /**
     * Returns the sample value in the specified band of current pixel, rounded toward zero.
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code getSample(int)} method is invoked. If above condition is not met,
     * then this method behavior is undefined: it may throw any runtime exception or return a meaningless value
     * (there is no explicit bounds check for performance reasons).
     *
     * @param  band  the band for which to get the sample value.
     * @return sample value in specified band of current pixel.
     *
     * @see Raster#getSample(int, int, int)
     */
    public abstract int getSample(int band);

    /**
     * Returns the sample value in the specified band of current pixel as a single-precision floating point number.
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code getSampleFloat(int)} method is invoked. If above condition is
     * not met, then this method behavior is undefined: it may throw any runtime exception or return a meaningless
     * value (there is no explicit bounds check for performance reasons).
     *
     * @param  band  the band for which to get the sample value.
     * @return sample value in specified band of current pixel.
     *
     * @see Raster#getSampleFloat(int, int, int)
     */
    public abstract float getSampleFloat(int band);

    /**
     * Returns the sample value in the specified band of current pixel, without precision lost.
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code getSampleDouble(int)} method is invoked. If above condition is
     * not met, then this method behavior is undefined: it may throw any runtime exception or return a meaningless
     * value (there is no explicit bounds check for performance reasons).
     *
     * @param  band  the band for which to get the sample value.
     * @return sample value in specified band of current pixel.
     *
     * @see Raster#getSampleDouble(int, int, int)
     */
    public abstract double getSampleDouble(int band);

    /**
     * Returns the sample values of current pixel for all bands.
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code getPixel(…)} method is invoked. If above condition is not met,
     * then this method behavior is undefined: it may throw any runtime exception or return a meaningless value
     * (there is no explicit bounds check for performance reasons).
     *
     * @param  dest  a pre-allocated array where to store the sample values, or {@code null} if none.
     * @return the sample values for current pixel.
     *
     * @see Raster#getPixel(int, int, double[])
     */
    public abstract double[] getPixel​(double[] dest);

    /**
     * Returns the sample values of current pixel for all bands.
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code getPixel(…)} method is invoked. If above condition is not met,
     * then this method behavior is undefined: it may throw any runtime exception or return a meaningless value
     * (there is no explicit bounds check for performance reasons).
     *
     * @param  dest  a pre-allocated array where to store the sample values, or {@code null} if none.
     * @return the sample values for current pixel.
     *
     * @see Raster#getPixel(int, int, float[])
     */
    public abstract float[] getPixel​(float[] dest);

    /**
     * Returns the sample values of current pixel for all bands.
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code getPixel(…)} method is invoked. If above condition is not met,
     * then this method behavior is undefined: it may throw any runtime exception or return a meaningless value
     * (there is no explicit bounds check for performance reasons).
     *
     * @param  dest  a pre-allocated array where to store the sample values, or {@code null} if none.
     * @return the sample values for current pixel.
     *
     * @see Raster#getPixel(int, int, int[])
     */
    public abstract int[] getPixel​(int[] dest);

    /**
     * Returns the sample values in a rectangular region starting at the current pixel position.
     * The region size is the <cite>window size</cite> specified at {@code PixelIterator} construction time.
     * The length of the returned array will be
     * <var>(number of bands)</var> × <var>(window width)</var> × <var>(window height)</var>.
     * Values are always stored with band index varying fastest, then column index, then row index.
     * Columns are traversed from left to right and rows are traversed from top to bottom
     * ({@link SequenceType#LINEAR} iteration order).
     * That order is the same regardless the {@linkplain #getIterationOrder() iteration order} of this iterator.
     *
     * <div class="note"><b>Example:</b>
     * for an RGB image, the 3 first values are the red, green and blue components of the pixel at
     * {@linkplain #getPosition() current iterator position}. The 3 next values are the red, green
     * and blue components of the pixel at the right of current iterator position, <i>etc.</i></div>
     *
     * The returned region will be live: calls to {@link #next()} followed by {@code window()} returns an array
     * (potentially the same array instance than previous call) updated with values starting at the new iterator
     * position. The returned array is valid only until the next call to {@code window()}. The array shall only
     * be read; behavior of this method become unspecified if caller modifies any values in the array.
     *
     * <div class="note"><b>Rational:</b>
     * the read-only constraint on the returned array exists for performance reasons.
     * This method may recycle the same array in a way that avoid fetching existing values.
     * </div>
     *
     * The {@link #next()} method must have returned {@code true}, or the {@link #moveTo(int,int)} method must have
     * been invoked successfully, before this {@code window()} method is invoked. If above condition is not met,
     * then this method behavior is undefined: it may throw any runtime exception or return meaningless values
     * (there is no explicit bounds check for performance reasons).
     *
     * @return the sample values in the region starting at current iterator position.
     *
     * @see Raster#getPixels(int, int, int, int, double[])
     */
    public abstract double[] window();

    /**
     * Restores the iterator to the start position. After this method has been invoked,
     * the iterator is in the same state than after construction.
     */
    public abstract void rewind();
}