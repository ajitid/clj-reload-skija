(ns lib.color.spectral
  "Spectral (pigment) color mixing using Kubelka-Munk theory.

   Ported from spectral.js v3 by Ronald van Wijnen (MIT License).
   https://github.com/rvanwijnen/spectral.js

   Blue + yellow = green (not gray). Paint-like mixing on both CPU and GPU.

   CPU API:
     (spectral/mix color1 color2 t)   ;; [r g b a] in 0-1, returns [r g b a]

   GPU API:
     (spectral/blender t)             ;; SkSL Blender for use with paint opts
     (spectral/blender-effect)        ;; compiled RuntimeEffect for reuse"
  (:require [lib.graphics.shaders :as shaders]))

;; ============================================================
;; Constants — 38-band spectral data from spectral.js v3
;; ============================================================

(def ^:private ^:const SIZE 38)
(def ^:private ^:const GAMMA 2.4)

;; Base spectra for RGB decomposition into white + CMYRGB

(def ^:private spectra-W
  (double-array
    [1.00116072718764 1.00116065159728 1.00116031922747 1.00115867270789
     1.00115259844552 1.00113252528998 1.00108500663327 1.00099687889453
     1.00086525152274 1.0006962900094  1.00050496114888 1.00030808187992
     1.00011966602013 0.999952765968407 0.999821836899297 0.999738609557593
     0.999709551639612 0.999731930210627 0.999799436346195 0.999900330316671
     1.00002040652611 1.00014478793658 1.00025997903412 1.00035579697089
     1.00042753780269 1.00047623344888 1.00050720967508 1.00052519156373
     1.00053509606896 1.00054022097482 1.00054272816784 1.00054389569087
     1.00054448212151 1.00054476959992 1.00054489887762 1.00054496254689
     1.00054498927058 1.000544996993]))

(def ^:private spectra-C
  (double-array
    [0.970585001322962 0.970592498143425 0.970625348729891 0.970786806119017
     0.971368673228248 0.973163230621252 0.976740223158765 0.981587605491377
     0.986280265652949 0.989949147689134 0.99249270153842  0.994145680405256
     0.995183975033212 0.995756750110818 0.99591281828671  0.995606157834528
     0.994597600961854 0.99221571549237  0.986236452783249 0.967943337264541
     0.891285004244943 0.536202477862053 0.154108119001878 0.0574575093228929
     0.0315349873107007 0.0222633920086335 0.0182022841492439 0.016299055973264
     0.0153656239334613 0.0149111568733976 0.0146954339898235 0.0145964146717719
     0.0145470156699655 0.0145228771899495 0.0145120341118965 0.0145066940939832
     0.0145044507314479 0.0145038009464639]))

(def ^:private spectra-M
  (double-array
    [0.990673557319988 0.990671524961979 0.990662582353421 0.990618107644795
     0.99045148087871  0.989871081400204 0.98828660875964  0.984290692797504
     0.973934905625306 0.941817838460145 0.817390326195156 0.432472805065729
     0.13845397825887  0.0537347216940033 0.0292174996673231 0.021313651750859
     0.0201349530181136 0.0241323096280662 0.0372236145223627 0.0760506552706601
     0.205375471942399 0.541268903460439 0.815841685086486 0.912817704123976
     0.946339830166962 0.959927696331991 0.966260595230312 0.969325970058424
     0.970854536721399 0.971605066528128 0.971962769757392 0.972127272274509
     0.972209417745812 0.972249577678424 0.972267621998742 0.97227650946215
     0.972280243306874 0.97228132482656]))

(def ^:private spectra-Y
  (double-array
    [0.0210523371789306 0.0210564627517414 0.0210746178695038 0.0211649058448753
     0.0215027957272504 0.0226738799041561 0.0258235649693629 0.0334879385639851
     0.0519069663740307 0.100749014833473  0.239129899706847  0.534804312272748
     0.79780757864303   0.911449894067384  0.953797963004507  0.971241615465429
     0.979303123807588  0.983380119507575  0.985461246567755  0.986435046976605
     0.986738250670141  0.986617882445032  0.986277776758643  0.985860592444056
     0.98547492767621   0.985176934765558  0.984971574014181  0.984846303415712
     0.984775351811199  0.984738066625265  0.984719648311765  0.984711023391939
     0.984706683300676  0.984704554393091  0.98470359630937   0.984703124077552
     0.98470292561509   0.984702868122795]))

(def ^:private spectra-R
  (double-array
    [0.0315605737777207 0.0315520718330149 0.0315148215513658 0.0313318044982702
     0.0306729857725527 0.0286480476989607 0.0246450407045709 0.0192960753663651
     0.0142066612220556 0.0102942608878609 0.0076191460521811 0.005898041083542
     0.0048233247781713 0.0042298748350633 0.0040599171299341 0.0043533695594676
     0.0053434425970201 0.0076917201010463 0.0135969795736536 0.0316975442661115
     0.107861196355249  0.463812603168704  0.847055405272011  0.943185409393918
     0.968862150696558  0.978030667473603  0.982043643854306  0.983923623718707
     0.984845484154382  0.985294275814596  0.985507295219825  0.985605071539837
     0.985653849933578  0.985677685033883  0.985688391806122  0.985693664690031
     0.985695879848205  0.985696521463762]))

(def ^:private spectra-G
  (double-array
    [0.0095560747554212 0.0095581580120851 0.0095673245444588 0.0096129126297349
     0.0097837090401843 0.010378622705871  0.0120026452378567 0.0160977721473922
     0.026706190223168  0.0595555440185881 0.186039826532826  0.570579820116159
     0.861467768400292  0.945879089767658  0.970465486474305  0.97841363028445
     0.979589031411224  0.975533536908632  0.962288755397813  0.92312157451312
     0.793434018943111  0.459270135902429  0.185574103666303  0.0881774959955372
     0.05436302287667   0.0406288447060719 0.034221520431697  0.0311185790956966
     0.0295708898336134 0.0288108739348928 0.0284486271324597 0.0282820301724731
     0.0281988376490237 0.0281581655342037 0.0281398910216386 0.0281308901665811
     0.0281271086805816 0.0281260133612096]))

(def ^:private spectra-B
  (double-array
    [0.979404752502014  0.97940070684313   0.979382903470261  0.979294364945594
     0.97896301460857   0.977814466694043  0.974724321133836  0.967198482343973
     0.949079657530575  0.900850128940977  0.76315044546224   0.465922171649319
     0.201263280451005  0.0877524413419623 0.0457176793291679 0.0284706050521843
     0.020527176756985  0.0165302792310211 0.0145135107212858 0.0136003508637687
     0.0133604258769571 0.013548894314568  0.0139594356366992 0.014443425575357
     0.0148854440621406 0.0152254296999746 0.0154592848180209 0.0156018026485961
     0.0156824871281936 0.0157248764360615 0.0157458108784121 0.0157556123350225
     0.0157605443964911 0.0157629637515278 0.0157640525629106 0.015764589232951
     0.0157648147772649 0.0157648801149616]))

;; CIE 1931 Color Matching Functions (weighted by D65 illuminant)
;; 3 rows × 38 columns: X, Y, Z

(def ^:private cmf-X
  (double-array
    [0.0000646919989576 0.0002194098998132 0.0011205743509343 0.0037666134117111
     0.011880553603799  0.0232864424191771 0.0345594181969747 0.0372237901162006
     0.0324183761091486 0.021233205609381  0.0104909907685421 0.0032958375797931
     0.0005070351633801 0.0009486742057141 0.0062737180998318 0.0168646241897775
     0.028689649025981  0.0426748124691731 0.0562547481311377 0.0694703972677158
     0.0830531516998291 0.0861260963002257 0.0904661376847769 0.0850038650591277
     0.0709066691074488 0.0506288916373645 0.035473961885264  0.0214682102597065
     0.0125164567619117 0.0068045816390165 0.0034645657946526 0.0014976097506959
     0.000769700480928  0.0004073680581315 0.0001690104031614 0.0000952245150365
     0.0000490309872958 0.0000199961492222]))

(def ^:private cmf-Y
  (double-array
    [0.000001844289444  0.0000062053235865 0.0000310096046799 0.0001047483849269
     0.0003536405299538 0.0009514714056444 0.0022822631748318 0.004207329043473
     0.0066887983719014 0.0098883960193565 0.0152494514496311 0.0214183109449723
     0.0334229301575068 0.0513100134918512 0.070402083939949  0.0878387072603517
     0.0942490536184085 0.0979566702718931 0.0941521856862608 0.0867810237486753
     0.0788565338632013 0.0635267026203555 0.05374141675682   0.042646064357412
     0.0316173492792708 0.020885205921391  0.0138601101360152 0.0081026402038399
     0.004630102258803  0.0024913800051319 0.0012593033677378 0.000541646522168
     0.0002779528920067 0.0001471080673854 0.0000610327472927 0.0000343873229523
     0.0000177059860053 0.000007220974913]))

(def ^:private cmf-Z
  (double-array
    [0.000305017147638  0.0010368066663574 0.0053131363323992 0.0179543925899536
     0.0570775815345485 0.113651618936287  0.17335872618355   0.196206575558657
     0.186082370706296  0.139950475383207  0.0891745294268649 0.0478962113517075
     0.0281456253957952 0.0161376622950514 0.0077591019215214 0.0042961483736618
     0.0020055092122156 0.0008614711098802 0.0003690387177652 0.0001914287288574
     0.0001495555858975 0.0000923109285104 0.0000681349182337 0.0000288263655696
     0.0000157671820553 0.0000039406041027 0.000001584012587  0.0 0.0 0.0
     0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]))

;; XYZ → sRGB conversion matrix (from spectral.js CONVERSION.XYZ_RGB)
(def ^:private xyz-to-rgb-mat
  [[ 3.2409699419045226 -1.537383177570094  -0.4986107602930034]
   [-0.9692436362808796  1.8759675015077202  0.04155505740717559]
   [ 0.05563007969699366 -0.20397695888897652 1.0569715142428786]])

;; ============================================================
;; CPU-side spectral mixing
;; ============================================================

(defn- uncompand
  "sRGB → linear (inverse gamma)."
  ^double [^double x]
  (if (> x 0.04045)
    (Math/pow (/ (+ x 0.055) 1.055) GAMMA)
    (/ x 12.92)))

(defn- compand
  "linear → sRGB (gamma)."
  ^double [^double x]
  (if (> x 0.0031308)
    (- (* 1.055 (Math/pow x (/ 1.0 GAMMA))) 0.055)
    (* x 12.92)))

(defn- linear-to-reflectance
  "Decompose linear RGB into 38-band reflectance via white+CMYRGB weights."
  ^doubles [^double lr ^double lg ^double lb]
  (let [w (min lr lg lb)
        lr (- lr w)
        lg (- lg w)
        lb (- lb w)
        c (min lg lb)
        m (min lr lb)
        y (min lr lg)
        r (max 0.0 (min (- lr lb) (- lr lg)))
        g (max 0.0 (min (- lg lb) (- lg lr)))
        b (max 0.0 (min (- lb lg) (- lb lr)))
        result (double-array SIZE)]
    (dotimes [i SIZE]
      (aset result i
            (max Double/MIN_VALUE
                 (+ (* w (aget spectra-W i))
                    (* c (aget spectra-C i))
                    (* m (aget spectra-M i))
                    (* y (aget spectra-Y i))
                    (* r (aget spectra-R i))
                    (* g (aget spectra-G i))
                    (* b (aget spectra-B i))))))
    result))

(defn- reflectance-to-xyz
  "38-band reflectance → CIE XYZ via color matching functions."
  ^doubles [^doubles R]
  (let [x (double-array 1)
        y (double-array 1)
        z (double-array 1)]
    (dotimes [i SIZE]
      (let [ri (aget R i)]
        (aset x 0 (+ (aget x 0) (* ri (aget cmf-X i))))
        (aset y 0 (+ (aget y 0) (* ri (aget cmf-Y i))))
        (aset z 0 (+ (aget z 0) (* ri (aget cmf-Z i))))))
    (double-array [(aget x 0) (aget y 0) (aget z 0)])))

(defn- xyz-to-srgb
  "CIE XYZ → sRGB [r g b] clamped 0-1."
  [^doubles xyz]
  (let [x (aget xyz 0)
        y (aget xyz 1)
        z (aget xyz 2)
        ;; Matrix multiply
        lr (+ (* 3.2409699419045226 x) (* -1.537383177570094 y) (* -0.4986107602930034 z))
        lg (+ (* -0.9692436362808796 x) (* 1.8759675015077202 y) (* 0.04155505740717559 z))
        lb (+ (* 0.05563007969699366 x) (* -0.20397695888897652 y) (* 1.0569715142428786 z))]
    [(max 0.0 (min 1.0 (compand lr)))
     (max 0.0 (min 1.0 (compand lg)))
     (max 0.0 (min 1.0 (compand lb)))]))

(defn- ks
  "Kubelka-Munk absorption/scattering ratio."
  ^double [^double R]
  (/ (* (- 1.0 R) (- 1.0 R)) (* 2.0 R)))

(defn- km
  "Kubelka-Munk reflectance from KS value."
  ^double [^double ks-val]
  (+ 1.0 ks-val (- (Math/sqrt (+ (* ks-val ks-val) (* 2.0 ks-val))))))

(defn mix
  "Spectrally mix two colors using Kubelka-Munk pigment theory.

   Colors are [r g b a] vectors with values in 0.0-1.0 range.
   t is the blend factor: 0.0 = color1, 1.0 = color2.

   Returns [r g b a] with alpha linearly interpolated.

   Example:
     (mix [0.0 0.0 1.0 1.0] [1.0 1.0 0.0 1.0] 0.5)
     ;; => vivid green (not gray!)"
  [[r1 g1 b1 a1] [r2 g2 b2 a2] t]
  (let [t (double t)
        t1 (- 1.0 t)
        ;; Convert sRGB → linear
        lr1 (uncompand (double r1)) lg1 (uncompand (double g1)) lb1 (uncompand (double b1))
        lr2 (uncompand (double r2)) lg2 (uncompand (double g2)) lb2 (uncompand (double b2))
        ;; Convert to reflectance
        R1 (linear-to-reflectance lr1 lg1 lb1)
        R2 (linear-to-reflectance lr2 lg2 lb2)
        ;; Luminance (Y from XYZ) for concentration weighting
        lum1 (max Double/MIN_VALUE (aget (reflectance-to-xyz R1) 1))
        lum2 (max Double/MIN_VALUE (aget (reflectance-to-xyz R2) 1))
        ;; Concentration weights (factor² × luminance, tinting strength = 1)
        conc1 (* t1 t1 lum1)
        conc2 (* t t lum2)
        total (+ conc1 conc2)
        ;; Mix in KS space, convert back via KM
        mixed-R (double-array SIZE)]
    (dotimes [i SIZE]
      (let [ks-mix (/ (+ (* (ks (aget R1 i)) conc1)
                         (* (ks (aget R2 i)) conc2))
                      total)]
        (aset mixed-R i (km ks-mix))))
    ;; Reflectance → XYZ → sRGB
    (let [[mr mg mb] (xyz-to-srgb (reflectance-to-xyz mixed-R))
          ;; Linearly interpolate alpha
          ma (+ (* t1 (double (or a1 1.0))) (* t (double (or a2 1.0))))]
      [mr mg mb ma])))

;; ============================================================
;; GPU-side SkSL — shared spectral core + blender + shader
;; ============================================================

;; Generate SkSL array init code: "name[0]=v; name[1]=v; ..."
(defn- gen-array-init [^String name ^doubles arr]
  (let [sb (StringBuilder.)]
    (.append sb (str "float " name "[38];\n"))
    (dotimes [i (alength arr)]
      (.append sb (format "%s[%d]=%.17g; " name (int i) (aget arr i)))
      (when (= 3 (mod i 4)) (.append sb "\n")))
    (.append sb "\n")
    (.toString sb)))

;; Shared SkSL: helper functions + spectral_mix_rgb
(def ^:private spectral-sksl-core
  (str
    "// Spectral color mixing (Kubelka-Munk)\n"
    "// Ported from spectral.js v3 by Ronald van Wijnen\n"
    "// MIT License - Copyright (c) 2025 Ronald van Wijnen\n"
    "// https://github.com/rvanwijnen/spectral.js\n\n"

    "float spectral_uncompand(float x) {\n"
    "  return (x > 0.04045) ? pow((x + 0.055) / 1.055, 2.4) : x / 12.92;\n"
    "}\n\n"

    "float spectral_compand(float x) {\n"
    "  return (x > 0.0031308) ? 1.055 * pow(x, 1.0 / 2.4) - 0.055 : x * 12.92;\n"
    "}\n\n"

    "float spectral_ks(float r) {\n"
    "  return (1.0 - r) * (1.0 - r) / (2.0 * r);\n"
    "}\n\n"

    "float spectral_km(float ks) {\n"
    "  return 1.0 + ks - sqrt(ks * ks + 2.0 * ks);\n"
    "}\n\n"

    "float3 xyz_to_srgb(float3 xyz) {\n"
    "  float lr =  3.2409699419045226 * xyz.x - 1.537383177570094  * xyz.y - 0.4986107602930034 * xyz.z;\n"
    "  float lg = -0.9692436362808796 * xyz.x + 1.8759675015077202 * xyz.y + 0.04155505740717559 * xyz.z;\n"
    "  float lb =  0.05563007969699366 * xyz.x - 0.20397695888897652 * xyz.y + 1.0569715142428786 * xyz.z;\n"
    "  return float3(\n"
    "    clamp(spectral_compand(lr), 0.0, 1.0),\n"
    "    clamp(spectral_compand(lg), 0.0, 1.0),\n"
    "    clamp(spectral_compand(lb), 0.0, 1.0));\n"
    "}\n\n"

    "float3 spectral_mix_rgb(float3 color1, float3 color2, float factor) {\n"
    "  float lr1 = spectral_uncompand(color1.r);\n"
    "  float lg1 = spectral_uncompand(color1.g);\n"
    "  float lb1 = spectral_uncompand(color1.b);\n"
    "  float lr2 = spectral_uncompand(color2.r);\n"
    "  float lg2 = spectral_uncompand(color2.g);\n"
    "  float lb2 = spectral_uncompand(color2.b);\n\n"

    "  float w1 = min(lr1, min(lg1, lb1));\n"
    "  lr1 -= w1; lg1 -= w1; lb1 -= w1;\n"
    "  float c1 = min(lg1, lb1); float m1 = min(lr1, lb1); float y1 = min(lr1, lg1);\n"
    "  float r1 = max(0.0, min(lr1 - lb1, lr1 - lg1));\n"
    "  float g1 = max(0.0, min(lg1 - lb1, lg1 - lr1));\n"
    "  float b1 = max(0.0, min(lb1 - lg1, lb1 - lr1));\n\n"

    "  float w2 = min(lr2, min(lg2, lb2));\n"
    "  lr2 -= w2; lg2 -= w2; lb2 -= w2;\n"
    "  float c2 = min(lg2, lb2); float m2 = min(lr2, lb2); float y2 = min(lr2, lg2);\n"
    "  float r2 = max(0.0, min(lr2 - lb2, lr2 - lg2));\n"
    "  float g2 = max(0.0, min(lg2 - lb2, lg2 - lr2));\n"
    "  float b2 = max(0.0, min(lb2 - lg2, lb2 - lr2));\n\n"

    (gen-array-init "sW" spectra-W)
    (gen-array-init "sC" spectra-C)
    (gen-array-init "sM" spectra-M)
    (gen-array-init "sY" spectra-Y)
    (gen-array-init "sR" spectra-R)
    (gen-array-init "sG" spectra-G)
    (gen-array-init "sB" spectra-B)
    (gen-array-init "cX" cmf-X)
    (gen-array-init "cY" cmf-Y)
    (gen-array-init "cZ" cmf-Z)

    "  float R1[38]; float R2[38];\n"
    "  float lum1 = 0.0; float lum2 = 0.0;\n\n"

    "  for (int i = 0; i < 38; i++) {\n"
    "    R1[i] = max(1e-30, w1*sW[i] + c1*sC[i] + m1*sM[i] + y1*sY[i] + r1*sR[i] + g1*sG[i] + b1*sB[i]);\n"
    "    R2[i] = max(1e-30, w2*sW[i] + c2*sC[i] + m2*sM[i] + y2*sY[i] + r2*sR[i] + g2*sG[i] + b2*sB[i]);\n"
    "    lum1 += R1[i] * cY[i];\n"
    "    lum2 += R2[i] * cY[i];\n"
    "  }\n\n"

    "  lum1 = max(1e-30, lum1); lum2 = max(1e-30, lum2);\n"
    "  float t1 = 1.0 - factor;\n"
    "  float conc1 = t1 * t1 * lum1;\n"
    "  float conc2 = factor * factor * lum2;\n"
    "  float total = conc1 + conc2;\n\n"

    "  float3 xyz = float3(0.0);\n"
    "  for (int i = 0; i < 38; i++) {\n"
    "    float ks_mix = (spectral_ks(R1[i]) * conc1 + spectral_ks(R2[i]) * conc2) / total;\n"
    "    float mv = spectral_km(ks_mix);\n"
    "    xyz.x += mv * cX[i];\n"
    "    xyz.y += mv * cY[i];\n"
    "    xyz.z += mv * cZ[i];\n"
    "  }\n\n"

    "  return xyz_to_srgb(xyz);\n"
    "}\n\n"))

;; --- Blender (src/dst paint mixing) ---

(def ^:private spectral-blender-sksl
  (str
    "uniform float t;\n\n"
    spectral-sksl-core
    "half4 main(half4 src, half4 dst) {\n"
    "  float3 sc = (src.a > 0.0) ? src.rgb / src.a : float3(0.0);\n"
    "  float3 dc = (dst.a > 0.0) ? dst.rgb / dst.a : float3(0.0);\n"
    "  float3 mixed = spectral_mix_rgb(dc, sc, t);\n"
    "  float a = mix(dst.a, src.a, t);\n"
    "  return half4(half3(mixed * a), half(a));\n"
    "}\n"))

(def ^:private spectral-blender-effect*
  (delay (shaders/effect :blender spectral-blender-sksl)))

(defn blender-effect
  "Get the compiled RuntimeEffect for the spectral blender.
   Compiled lazily on first use, cached thereafter."
  []
  @spectral-blender-effect*)

(defn blender
  "Create a spectral color mixing blender for use with Skia paint.

   t is the blend factor: 0.0 = dst color, 1.0 = src color.

   Usage with shapes:
     (shapes/circle canvas x y r {:color [1 0 0 1]
                                   :blender (spectral/blender 0.5)})

   The blender mixes the paint color (src) with the existing canvas
   content (dst) using Kubelka-Munk pigment theory on the GPU."
  [t]
  (shaders/make-blender (blender-effect) {:t (double t)}))

;; --- Gradient shader (horizontal spectral gradient, fully GPU) ---

(def ^:private spectral-gradient-sksl
  (str
    "uniform float4 uColor1;\n"
    "uniform float4 uColor2;\n"
    "uniform float uWidth;\n\n"
    spectral-sksl-core
    "half4 main(float2 coord) {\n"
    "  float t = clamp(coord.x / uWidth, 0.0, 1.0);\n"
    "  float3 mixed = spectral_mix_rgb(uColor1.rgb, uColor2.rgb, t);\n"
    "  return half4(half3(mixed), 1.0);\n"
    "}\n"))

(def ^:private spectral-gradient-effect*
  (delay (shaders/effect spectral-gradient-sksl)))

(defn gradient-shader
  "Create a shader that renders a horizontal spectral gradient between two colors.
   Entire computation runs on the GPU — one draw call, no CPU mixing.

   Args:
     color1 - [r g b a] start color (left edge)
     color2 - [r g b a] end color (right edge)
     width  - gradient width in pixels"
  [[r1 g1 b1 a1] [r2 g2 b2 a2] width]
  (shaders/make-shader @spectral-gradient-effect*
                       {:uColor1 [(double r1) (double g1) (double b1) (double (or a1 1.0))]
                        :uColor2 [(double r2) (double g2) (double b2) (double (or a2 1.0))]
                        :uWidth  (double width)}))
