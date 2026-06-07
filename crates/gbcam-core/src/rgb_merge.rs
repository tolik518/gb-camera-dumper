const IMAGE_WIDTH: usize = 128;
const IMAGE_HEIGHT: usize = 112;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RgbMergeAlgorithm {
    Basic,
    ClearLum,
    Norm,
    NormClearLum,
    SatBoost,
    GrayWorld,
    BroveyClear,
    IhsClear,
    DetailClear,
    Adaptive,
}

impl RgbMergeAlgorithm {
    pub fn from_id(id: &str) -> Option<Self> {
        match id {
            "basic" => Some(Self::Basic),
            "clear_lum" => Some(Self::ClearLum),
            "norm" => Some(Self::Norm),
            "norm_clear_lum" => Some(Self::NormClearLum),
            "sat_boost" => Some(Self::SatBoost),
            "gray_world" => Some(Self::GrayWorld),
            "brovey_clear" => Some(Self::BroveyClear),
            "ihs_clear" => Some(Self::IhsClear),
            "detail_clear" => Some(Self::DetailClear),
            "adaptive" => Some(Self::Adaptive),
            _ => None,
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Self::Basic => "basic",
            Self::ClearLum => "clear_lum",
            Self::Norm => "norm",
            Self::NormClearLum => "norm_clear_lum",
            Self::SatBoost => "sat_boost",
            Self::GrayWorld => "gray_world",
            Self::BroveyClear => "brovey_clear",
            Self::IhsClear => "ihs_clear",
            Self::DetailClear => "detail_clear",
            Self::Adaptive => "adaptive",
        }
    }

    pub fn resolve(id: &str, has_clear: bool) -> Self {
        match Self::from_id(id) {
            Some(algorithm) if algorithm.compatible_with(has_clear) => algorithm,
            _ if has_clear => Self::NormClearLum,
            _ => Self::Norm,
        }
    }

    pub fn compatible_with(self, has_clear: bool) -> bool {
        if has_clear {
            true
        } else {
            !matches!(
                self,
                Self::ClearLum
                    | Self::NormClearLum
                    | Self::BroveyClear
                    | Self::IhsClear
                    | Self::DetailClear
            )
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RgbMergeChannel {
    Clear,
    Red,
    Green,
    Blue,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RgbMergeOrder {
    label: String,
    channels: Vec<RgbMergeChannel>,
}

impl RgbMergeOrder {
    pub fn parse(order: &str) -> Result<Self, RgbMergeError> {
        let mut channels = Vec::with_capacity(order.len());
        let mut clear = false;
        let mut red = false;
        let mut green = false;
        let mut blue = false;
        for ch in order.chars() {
            match ch {
                'C' if !clear => {
                    clear = true;
                    channels.push(RgbMergeChannel::Clear);
                }
                'R' if !red => {
                    red = true;
                    channels.push(RgbMergeChannel::Red);
                }
                'G' if !green => {
                    green = true;
                    channels.push(RgbMergeChannel::Green);
                }
                'B' if !blue => {
                    blue = true;
                    channels.push(RgbMergeChannel::Blue);
                }
                _ => return Err(RgbMergeError::InvalidOrder(order.to_string())),
            }
        }
        let valid = match channels.len() {
            3 => red && green && blue && !clear,
            4 => red && green && blue && clear,
            _ => false,
        };
        if !valid {
            return Err(RgbMergeError::InvalidOrder(order.to_string()));
        }
        Ok(Self {
            label: order.to_string(),
            channels,
        })
    }

    pub fn label(&self) -> &str {
        &self.label
    }

    pub fn source_count(&self) -> usize {
        self.channels.len()
    }

    pub fn has_clear(&self) -> bool {
        self.clear_index().is_some()
    }

    fn clear_index(&self) -> Option<usize> {
        self.index_of(RgbMergeChannel::Clear)
    }

    fn red_index(&self) -> usize {
        self.index_of(RgbMergeChannel::Red).unwrap()
    }

    fn green_index(&self) -> usize {
        self.index_of(RgbMergeChannel::Green).unwrap()
    }

    fn blue_index(&self) -> usize {
        self.index_of(RgbMergeChannel::Blue).unwrap()
    }

    fn index_of(&self, channel: RgbMergeChannel) -> Option<usize> {
        self.channels
            .iter()
            .position(|&candidate| candidate == channel)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum RgbMergeError {
    #[error("invalid RGB merge order: {0}")]
    InvalidOrder(String),
    #[error("merge order expects {expected} sources, got {actual}")]
    SourceCount { expected: usize, actual: usize },
    #[error("merge source {index} has {actual} pixels, expected {expected}")]
    SourceSize {
        index: usize,
        expected: usize,
        actual: usize,
    },
}

pub fn merge_rgb_gray8(
    sources: &[&[u8]],
    order: &RgbMergeOrder,
    algorithm: RgbMergeAlgorithm,
) -> Result<Vec<u8>, RgbMergeError> {
    if sources.len() != order.source_count() {
        return Err(RgbMergeError::SourceCount {
            expected: order.source_count(),
            actual: sources.len(),
        });
    }
    let len = sources.first().map(|source| source.len()).unwrap_or(0);
    for (index, source) in sources.iter().enumerate() {
        if source.len() != len {
            return Err(RgbMergeError::SourceSize {
                index,
                expected: len,
                actual: source.len(),
            });
        }
    }

    let red = sources[order.red_index()];
    let green = sources[order.green_index()];
    let blue = sources[order.blue_index()];
    let clear = order.clear_index().map(|index| sources[index]);
    let algorithm = if algorithm.compatible_with(order.has_clear()) {
        algorithm
    } else {
        RgbMergeAlgorithm::resolve(algorithm.id(), order.has_clear())
    };

    let pixels = match algorithm {
        RgbMergeAlgorithm::Basic => compose_rgb(red, green, blue, None, 0.0),
        RgbMergeAlgorithm::ClearLum => compose_rgb(red, green, blue, clear, 1.0),
        RgbMergeAlgorithm::Norm => compose_rgb(
            &normalize(red),
            &normalize(green),
            &normalize(blue),
            None,
            0.0,
        ),
        RgbMergeAlgorithm::NormClearLum => {
            let clear = clear.map(normalize);
            compose_rgb(
                &normalize(red),
                &normalize(green),
                &normalize(blue),
                clear.as_deref(),
                0.65,
            )
        }
        RgbMergeAlgorithm::SatBoost => {
            let base = compose_rgb(
                &normalize(red),
                &normalize(green),
                &normalize(blue),
                None,
                0.0,
            );
            boost_saturation(&base, 1.25)
        }
        RgbMergeAlgorithm::GrayWorld => {
            let [r, g, b] = normalized_balanced_channels(red, green, blue);
            compose_rgb(&r, &g, &b, None, 0.0)
        }
        RgbMergeAlgorithm::BroveyClear => {
            let [r, g, b] = normalized_balanced_channels(red, green, blue);
            match clear {
                Some(clear) => brovey_fusion(&r, &g, &b, &normalize2(clear)),
                None => compose_rgb(&r, &g, &b, None, 0.0),
            }
        }
        RgbMergeAlgorithm::IhsClear => {
            let [r, g, b] = normalized_balanced_channels(red, green, blue);
            match clear {
                Some(clear) => replace_luma(&r, &g, &b, &normalize2(clear)),
                None => compose_rgb(&r, &g, &b, None, 0.0),
            }
        }
        RgbMergeAlgorithm::DetailClear => {
            let [r, g, b] = normalized_balanced_channels(red, green, blue);
            match clear {
                Some(clear) => inject_clear_detail(&r, &g, &b, &normalize2(clear)),
                None => compose_rgb(&r, &g, &b, None, 0.0),
            }
        }
        RgbMergeAlgorithm::Adaptive => {
            let mut r = normalize2(red);
            let mut g = normalize2(green);
            let mut b = normalize2(blue);
            balance_channels(&mut r, &mut g, &mut b);
            let clear = clear.map(normalize2);
            let base = compose_rgb(
                &r,
                &g,
                &b,
                clear.as_deref(),
                if clear.is_some() { 0.5 } else { 0.0 },
            );
            apply_brightness(&base, 1.15)
        }
    };
    Ok(pixels)
}

fn compose_rgb(r: &[u8], g: &[u8], b: &[u8], clear: Option<&[u8]>, clear_strength: f32) -> Vec<u8> {
    let mut pixels = Vec::with_capacity(r.len() * 3);
    for i in 0..r.len() {
        let cf = clear
            .map(|clear| (1.0 - clear_strength) + clear_strength * (clear[i] as f32 / 255.0))
            .unwrap_or(1.0);
        pixels.push(clamp((r[i] as f32 * cf).round() as i32));
        pixels.push(clamp((g[i] as f32 * cf).round() as i32));
        pixels.push(clamp((b[i] as f32 * cf).round() as i32));
    }
    pixels
}

fn normalized_balanced_channels(red: &[u8], green: &[u8], blue: &[u8]) -> [Vec<u8>; 3] {
    let mut r = normalize2(red);
    let mut g = normalize2(green);
    let mut b = normalize2(blue);
    balance_channels(&mut r, &mut g, &mut b);
    [r, g, b]
}

fn brovey_fusion(r: &[u8], g: &[u8], b: &[u8], pan: &[u8]) -> Vec<u8> {
    let mut pixels = Vec::with_capacity(r.len() * 3);
    for i in 0..r.len() {
        let sum = r[i] as f32 + g[i] as f32 + b[i] as f32;
        if sum <= 1.0 {
            pixels.extend_from_slice(&[pan[i], pan[i], pan[i]]);
            continue;
        }
        let scale = (3.0 * pan[i] as f32) / sum;
        pixels.push(clamp((r[i] as f32 * scale).round() as i32));
        pixels.push(clamp((g[i] as f32 * scale).round() as i32));
        pixels.push(clamp((b[i] as f32 * scale).round() as i32));
    }
    pixels
}

fn replace_luma(r: &[u8], g: &[u8], b: &[u8], luma: &[u8]) -> Vec<u8> {
    let mut pixels = Vec::with_capacity(r.len() * 3);
    for i in 0..r.len() {
        let y = 0.299 * r[i] as f32 + 0.587 * g[i] as f32 + 0.114 * b[i] as f32;
        let cr = r[i] as f32 - y;
        let cb = b[i] as f32 - y;
        let target = luma[i] as f32;
        let rv = clamp((target + cr).round() as i32);
        let bv = clamp((target + cb).round() as i32);
        let gv = clamp(((target - 0.299 * rv as f32 - 0.114 * bv as f32) / 0.587).round() as i32);
        pixels.extend_from_slice(&[rv, gv, bv]);
    }
    pixels
}

fn inject_clear_detail(r: &[u8], g: &[u8], b: &[u8], clear: &[u8]) -> Vec<u8> {
    let smooth = blur(clear);
    let mut pixels = Vec::with_capacity(r.len() * 3);
    for i in 0..r.len() {
        let ratio = (clear[i] as f32 + 16.0) / (smooth[i] as f32 + 16.0);
        let factor = 1.0 + 0.75 * (ratio - 1.0);
        pixels.push(clamp((r[i] as f32 * factor).round() as i32));
        pixels.push(clamp((g[i] as f32 * factor).round() as i32));
        pixels.push(clamp((b[i] as f32 * factor).round() as i32));
    }
    pixels
}

fn normalize(gray: &[u8]) -> Vec<u8> {
    normalize_percentile(gray, 0.01, 0.99)
}

fn normalize2(gray: &[u8]) -> Vec<u8> {
    normalize_percentile(gray, 0.02, 0.98)
}

fn normalize_percentile(gray: &[u8], p_low: f32, p_high: f32) -> Vec<u8> {
    let mut sorted = gray.to_vec();
    sorted.sort_unstable();
    let lo = sorted[((gray.len() as f32 * p_low) as usize).min(gray.len().saturating_sub(1))];
    let hi = sorted[((gray.len() as f32 * p_high) as usize).min(gray.len().saturating_sub(1))];
    if hi <= lo {
        return gray.to_vec();
    }
    let range = hi as i32 - lo as i32;
    gray.iter()
        .map(|&value| clamp(((value as i32 - lo as i32) * 255) / range))
        .collect()
}

fn balance_channels(r: &mut [u8], g: &mut [u8], b: &mut [u8]) {
    let mr = mean(r);
    let mg = mean(g);
    let mb = mean(b);
    let target = (mr + mg + mb) / 3.0;
    if mr > 1.0 {
        scale(r, target / mr);
    }
    if mg > 1.0 {
        scale(g, target / mg);
    }
    if mb > 1.0 {
        scale(b, target / mb);
    }
}

fn mean(values: &[u8]) -> f32 {
    values.iter().map(|&value| value as u64).sum::<u64>() as f32 / values.len() as f32
}

fn scale(values: &mut [u8], factor: f32) {
    for value in values {
        *value = clamp((*value as f32 * factor).round() as i32);
    }
}

fn apply_brightness(pixels: &[u8], factor: f32) -> Vec<u8> {
    pixels
        .iter()
        .map(|&value| clamp((value as f32 * factor).round() as i32))
        .collect()
}

fn boost_saturation(pixels: &[u8], sat_mult: f32) -> Vec<u8> {
    let mut result = Vec::with_capacity(pixels.len());
    for rgb in pixels.chunks_exact(3) {
        let (r, g, b) = boost_pixel_saturation(rgb[0], rgb[1], rgb[2], sat_mult);
        result.extend_from_slice(&[r, g, b]);
    }
    result
}

fn boost_pixel_saturation(r: u8, g: u8, b: u8, sat_mult: f32) -> (u8, u8, u8) {
    let rf = r as f32 / 255.0;
    let gf = g as f32 / 255.0;
    let bf = b as f32 / 255.0;
    let max = rf.max(gf).max(bf);
    let min = rf.min(gf).min(bf);
    let delta = max - min;
    if delta == 0.0 {
        return (r, g, b);
    }
    let value = max;
    let saturation = (delta / max * sat_mult).min(1.0);
    let hue = if max == rf {
        60.0 * (((gf - bf) / delta) % 6.0)
    } else if max == gf {
        60.0 * (((bf - rf) / delta) + 2.0)
    } else {
        60.0 * (((rf - gf) / delta) + 4.0)
    };
    hsv_to_rgb(hue, saturation, value)
}

fn hsv_to_rgb(hue: f32, saturation: f32, value: f32) -> (u8, u8, u8) {
    let hue = if hue < 0.0 { hue + 360.0 } else { hue };
    let c = value * saturation;
    let x = c * (1.0 - (((hue / 60.0) % 2.0) - 1.0).abs());
    let m = value - c;
    let (rp, gp, bp) = if hue < 60.0 {
        (c, x, 0.0)
    } else if hue < 120.0 {
        (x, c, 0.0)
    } else if hue < 180.0 {
        (0.0, c, x)
    } else if hue < 240.0 {
        (0.0, x, c)
    } else if hue < 300.0 {
        (x, 0.0, c)
    } else {
        (c, 0.0, x)
    };
    (
        clamp(((rp + m) * 255.0).round() as i32),
        clamp(((gp + m) * 255.0).round() as i32),
        clamp(((bp + m) * 255.0).round() as i32),
    )
}

fn blur(gray: &[u8]) -> Vec<u8> {
    if gray.len() == IMAGE_WIDTH * IMAGE_HEIGHT {
        return blur_dimensions(gray, IMAGE_WIDTH, IMAGE_HEIGHT);
    }
    let side = (gray.len() as f64).sqrt() as usize;
    if side * side != gray.len() {
        return gray.to_vec();
    }
    blur_dimensions(gray, side, side)
}

fn blur_dimensions(gray: &[u8], width: usize, height: usize) -> Vec<u8> {
    let mut out = vec![0; gray.len()];
    for y in 0..height {
        for x in 0..width {
            let mut sum = 0u32;
            let mut count = 0u32;
            for yy in y.saturating_sub(1)..=(y + 1).min(height - 1) {
                for xx in x.saturating_sub(1)..=(x + 1).min(width - 1) {
                    sum += gray[yy * width + xx] as u32;
                    count += 1;
                }
            }
            out[y * width + x] = (sum / count) as u8;
        }
    }
    out
}

fn clamp(value: i32) -> u8 {
    value.clamp(0, 255) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_rgb_and_crgb_orders() {
        let rgb = RgbMergeOrder::parse("BGR").unwrap();
        assert_eq!(rgb.source_count(), 3);
        assert!(!rgb.has_clear());
        assert_eq!(rgb.red_index(), 2);
        assert_eq!(rgb.green_index(), 1);
        assert_eq!(rgb.blue_index(), 0);

        let crgb = RgbMergeOrder::parse("GCRB").unwrap();
        assert_eq!(crgb.source_count(), 4);
        assert!(crgb.has_clear());
        assert_eq!(crgb.clear_index(), Some(1));
    }

    #[test]
    fn rejects_invalid_orders() {
        assert!(matches!(
            RgbMergeOrder::parse("RRG"),
            Err(RgbMergeError::InvalidOrder(_))
        ));
        assert!(matches!(
            RgbMergeOrder::parse("RGBX"),
            Err(RgbMergeError::InvalidOrder(_))
        ));
    }

    #[test]
    fn basic_merge_composes_channels_by_order() {
        let red = [10, 20];
        let green = [30, 40];
        let blue = [50, 60];
        let order = RgbMergeOrder::parse("GBR").unwrap();

        let rgb =
            merge_rgb_gray8(&[&green, &blue, &red], &order, RgbMergeAlgorithm::Basic).unwrap();

        assert_eq!(rgb, vec![10, 30, 50, 20, 40, 60]);
    }

    #[test]
    fn clear_lum_multiplies_channels_by_clear() {
        let clear = [128, 255];
        let red = [100, 100];
        let green = [50, 50];
        let blue = [200, 200];
        let order = RgbMergeOrder::parse("CRGB").unwrap();

        let rgb = merge_rgb_gray8(
            &[&clear, &red, &green, &blue],
            &order,
            RgbMergeAlgorithm::ClearLum,
        )
        .unwrap();

        assert_eq!(rgb, vec![50, 25, 100, 100, 50, 200]);
    }

    #[test]
    fn incompatible_clear_algorithm_resolves_to_non_clear_default() {
        let red = [0, 255];
        let green = [255, 0];
        let blue = [128, 128];
        let order = RgbMergeOrder::parse("RGB").unwrap();

        let rgb = merge_rgb_gray8(
            &[&red, &green, &blue],
            &order,
            RgbMergeAlgorithm::NormClearLum,
        )
        .unwrap();

        assert_eq!(rgb.len(), 6);
    }
}
