package com.sap.droidx.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sap.droidx.R
import com.sap.droidx.data.DeliveryRepository
import com.sap.droidx.databinding.ActivityLabelBinding
import kotlinx.coroutines.launch
import java.time.LocalDate

class LabelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLabelBinding
    private val repo = DeliveryRepository()

    private var currentDelivery: DeliveryRow? = null
    private var currentLabelType: LabelType? = null

    // ── Barcode scanner ──────────────────────────────────────────────────────
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let {
            binding.etDelivery.setText(it.take(10))
            loadDelivery(it.take(10))
        }
    }

    // ── Label types ──────────────────────────────────────────────────────────
    enum class LabelType(val layoutRes: Int, val title: String) {
        GS1(R.layout.layout_label_gs1,   "GS1 Label"),
        BOX(R.layout.layout_label_box,   "Box Label"),
        FEDEX(R.layout.layout_label_fedex, "FedEx Label"),
        UPS(R.layout.layout_label_ups,   "UPS Label"),
        USPS(R.layout.layout_label_usps, "USPS Label"),
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLabelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        binding.btnScan.setOnClickListener {
            scanLauncher.launch(ScanOptions().apply {
                setPrompt("Scan delivery barcode")
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }

        binding.etDelivery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val num = binding.etDelivery.text?.toString().orEmpty().trim()
                if (num.isNotEmpty()) loadDelivery(num)
                true
            } else false
        }

        binding.chipGroupLabelType.setOnCheckedStateChangeListener { _, checkedIds ->
            currentLabelType = when (checkedIds.firstOrNull()) {
                R.id.chipGS1   -> LabelType.GS1
                R.id.chipBox   -> LabelType.BOX
                R.id.chipFedEx -> LabelType.FEDEX
                R.id.chipUPS   -> LabelType.UPS
                R.id.chipUSPS  -> LabelType.USPS
                else           -> null
            }
            tryRenderLabel()
        }

        binding.btnPrint.setOnClickListener { printLabel() }
        binding.btnDemo.setOnClickListener { loadMockDelivery() }
    }

    // ── Mock data ─────────────────────────────────────────────────────────────
    private var mockIndex = 0

    private fun loadMockDelivery() {
        val mock = MOCK_DELIVERIES[mockIndex % MOCK_DELIVERIES.size]
        mockIndex++
        binding.etDelivery.setText(mock.deliveryNumber)
        currentDelivery = mock
        binding.tvDeliveryStatus.visibility = View.VISIBLE
        binding.tvDeliveryStatus.setTextColor(0xFF1565C0.toInt())
        binding.tvDeliveryStatus.text =
            "Demo: ${mock.deliveryNumber}  |  ${mock.createdBy}  |  ${mock.createdAt.take(10)}"
        tryRenderLabel()
    }

    // ── Data loading ──────────────────────────────────────────────────────────
    private fun loadDelivery(number: String) {
        binding.tvDeliveryStatus.visibility = View.VISIBLE
        binding.tvDeliveryStatus.setTextColor(0xFF888888.toInt())
        binding.tvDeliveryStatus.text = "Looking up delivery…"

        lifecycleScope.launch {
            runCatching { repo.listDeliveries() }
                .onSuccess { rows ->
                    val found = rows.find { it.deliveryNumber == number }
                    currentDelivery = found
                    if (found != null) {
                        binding.tvDeliveryStatus.setTextColor(0xFF2E7D32.toInt())
                        binding.tvDeliveryStatus.text = "✓  Found: ${found.deliveryNumber}  |  ${found.createdAt.take(10)}"
                    } else {
                        // Use placeholder so label can still be previewed
                        currentDelivery = DeliveryRow(
                            deliveryId = "", deliveryNumber = number, comments = "",
                            createdBy = "", createdAt = LocalDate.now().toString(), updatedAt = ""
                        )
                        binding.tvDeliveryStatus.setTextColor(0xFFE65100.toInt())
                        binding.tvDeliveryStatus.text = "⚠  Not found in system — showing template"
                    }
                    tryRenderLabel()
                }
                .onFailure {
                    // Still show label with what we have
                    currentDelivery = DeliveryRow(
                        deliveryId = "", deliveryNumber = number, comments = "",
                        createdBy = "", createdAt = LocalDate.now().toString(), updatedAt = ""
                    )
                    binding.tvDeliveryStatus.setTextColor(0xFFE65100.toInt())
                    binding.tvDeliveryStatus.text = "⚠  Offline — showing template"
                    tryRenderLabel()
                }
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────────
    private fun tryRenderLabel() {
        val type     = currentLabelType ?: return
        val delivery = currentDelivery  ?: return
        renderLabel(type, delivery)
    }

    private fun renderLabel(type: LabelType, delivery: DeliveryRow) {
        val prefs    = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val operator = prefs.getString(QuestionnaireActivity.KEY_NAME, "Operator") ?: "Operator"
        val site     = prefs.getString(QuestionnaireActivity.KEY_SITE, "Warehouse") ?: "Warehouse"
        val today    = LocalDate.now().toString()
        val tracking = fakeTracking(type, delivery.deliveryNumber)

        val container = binding.flLabelContainer
        container.removeAllViews()

        val view = layoutInflater.inflate(type.layoutRes, container, false)

        when (type) {
            LabelType.GS1   -> bindGS1(view, delivery, operator, site, today, tracking)
            LabelType.BOX   -> bindBox(view, delivery, operator, site, today, tracking)
            LabelType.FEDEX -> bindFedEx(view, delivery, operator, site, today, tracking)
            LabelType.UPS   -> bindUPS(view, delivery, operator, site, today, tracking)
            LabelType.USPS  -> bindUSPS(view, delivery, operator, site, today, tracking)
        }

        container.addView(view)
        binding.tvPreviewTitle.text = type.title
        binding.cardLabelPreview.visibility = View.VISIBLE

        binding.scrollView.post {
            binding.scrollView.smoothScrollTo(0, binding.cardLabelPreview.top)
        }
    }

    // ── Label data binders ────────────────────────────────────────────────────
    private fun bindGS1(v: View, d: DeliveryRow, op: String, site: String, today: String, tracking: String) {
        v.findViewById<TextView>(R.id.gsLabelDate).text      = today
        v.findViewById<TextView>(R.id.gsLabelDelivery).text  = d.deliveryNumber
        v.findViewById<TextView>(R.id.gsLabelOperator).text  = op
        v.findViewById<TextView>(R.id.gsLabelShipTo).text    = site
        v.findViewById<TextView>(R.id.gsLabelSite).text      = "DroidX Warehouse"
        v.findViewById<TextView>(R.id.gsLabelSSCC).text      = "(00) ${d.deliveryNumber.padStart(17, '0').chunked(1).joinToString(" ")}"
        v.findViewById<TextView>(R.id.gsLabelRemarks).text   = d.comments.ifBlank { "—" }
        barcode(v, R.id.gsBarcode, d.deliveryNumber)
    }

    private fun bindBox(v: View, d: DeliveryRow, op: String, site: String, today: String, tracking: String) {
        v.findViewById<TextView>(R.id.boxLabelDate).text         = today
        v.findViewById<TextView>(R.id.boxLabelDelivery).text     = d.deliveryNumber
        v.findViewById<TextView>(R.id.boxLabelFrom).text         = "$op\n$site"
        v.findViewById<TextView>(R.id.boxLabelTo).text           = "DroidX Warehouse\n$site"
        v.findViewById<TextView>(R.id.boxLabelDeliveryNum).text  = d.deliveryNumber
        v.findViewById<TextView>(R.id.boxLabelComments).text     = d.comments.ifBlank { "—" }
        barcode(v, R.id.boxBarcode, d.deliveryNumber)
    }

    private fun bindFedEx(v: View, d: DeliveryRow, op: String, site: String, today: String, tracking: String) {
        v.findViewById<TextView>(R.id.fedexTracking).text    = tracking
        v.findViewById<TextView>(R.id.fedexTrackingNum).text = tracking
        v.findViewById<TextView>(R.id.fedexFrom).text        = "$op\n$site"
        v.findViewById<TextView>(R.id.fedexTo).text          = "DroidX Warehouse\n$site"
        v.findViewById<TextView>(R.id.fedexDelivery).text    = d.deliveryNumber
        barcode(v, R.id.fedexBarcode, tracking)
    }

    private fun bindUPS(v: View, d: DeliveryRow, op: String, site: String, today: String, tracking: String) {
        v.findViewById<TextView>(R.id.upsTracking).text    = tracking
        v.findViewById<TextView>(R.id.upsTrackingNum).text = tracking
        v.findViewById<TextView>(R.id.upsFrom).text        = "$op\n$site"
        v.findViewById<TextView>(R.id.upsTo).text          = "DroidX Warehouse\n$site"
        v.findViewById<TextView>(R.id.upsDelivery).text    = d.deliveryNumber
        barcode(v, R.id.upsBarcode, tracking)
    }

    private fun bindUSPS(v: View, d: DeliveryRow, op: String, site: String, today: String, tracking: String) {
        v.findViewById<TextView>(R.id.uspsTracking).text    = tracking
        v.findViewById<TextView>(R.id.uspsTrackingNum).text = tracking
        v.findViewById<TextView>(R.id.uspsFrom).text        = "$op\n$site"
        v.findViewById<TextView>(R.id.uspsTo).text          = "DroidX Warehouse\n$site"
        v.findViewById<TextView>(R.id.uspsDelivery).text    = d.deliveryNumber
        barcode(v, R.id.uspsBarcode, tracking)
    }

    // ── Barcode helper ────────────────────────────────────────────────────────
    private fun barcode(view: View, imageViewId: Int, content: String) {
        try {
            val iv = view.findViewById<ImageView>(imageViewId)
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, 900, 200)
            iv.setImageBitmap(BarcodeEncoder().createBitmap(matrix))
        } catch (e: Exception) {
            Log.e(TAG, "Barcode encode failed", e)
        }
    }

    // ── Tracking number generator ─────────────────────────────────────────────
    private fun fakeTracking(type: LabelType, deliveryNumber: String): String {
        val seed = deliveryNumber.padStart(10, '0')
        return when (type) {
            LabelType.FEDEX -> "7489 ${seed.substring(0, 4)} ${seed.substring(4, 8)} ${seed.substring(8)}"
            LabelType.UPS   -> "1Z ${seed.substring(0, 3)} ${seed.substring(3, 6)} ${seed.substring(6, 10)} ${seed}"
            LabelType.USPS  -> "9400 1111 ${seed.substring(0, 4)} ${seed.substring(4, 8)} ${seed.substring(8)} 00"
            else            -> seed
        }
    }

    // ── Print ─────────────────────────────────────────────────────────────────
    private fun printLabel() {
        val container = binding.flLabelContainer
        if (container.childCount == 0) {
            Snackbar.make(binding.root, "Generate a label first", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labelView = container.getChildAt(0)

        // Force measure/layout so we can capture even before drawn
        labelView.measure(
            View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        labelView.layout(0, 0, labelView.measuredWidth, labelView.measuredHeight)

        val bmp = Bitmap.createBitmap(labelView.measuredWidth, labelView.measuredHeight, Bitmap.Config.ARGB_8888)
        labelView.draw(Canvas(bmp))

        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val jobName = "DroidX_${currentLabelType?.name ?: "Label"}_${currentDelivery?.deliveryNumber}"

        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttrs: PrintAttributes?,
                newAttrs: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                        .setPageCount(1)
                        .build(), true
                )
            }

            override fun onWrite(
                pageRanges: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                val pdf = PdfDocument()
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
                )
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdf.finishPage(page)
                try {
                    pdf.writeTo(ParcelFileDescriptor.AutoCloseOutputStream(destination!!))
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                } finally {
                    pdf.close()
                }
            }
        },
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A6)
                .setResolution(PrintAttributes.Resolution("300dpi", "300dpi", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
        )
    }

    companion object {
        private const val TAG = "LabelActivity"

        val MOCK_DELIVERIES = listOf(
            DeliveryRow(
                deliveryId     = "demo-001",
                deliveryNumber = "4200891234",
                comments       = "Inbound pallets from Supplier ABC — 12 boxes, handle with care",
                createdBy      = "J.MARTINEZ",
                createdAt      = "2026-04-28T08:15:00",
                updatedAt      = "2026-04-28T08:15:00"
            ),
            DeliveryRow(
                deliveryId     = "demo-002",
                deliveryNumber = "4200927461",
                comments       = "Electronics batch — no stacking, keep upright",
                createdBy      = "R.CHEN",
                createdAt      = "2026-04-27T11:42:00",
                updatedAt      = "2026-04-27T14:05:00"
            ),
            DeliveryRow(
                deliveryId     = "demo-003",
                deliveryNumber = "4201063052",
                comments       = "Automotive parts — 3 pallets, temperature sensitive",
                createdBy      = "A.PATEL",
                createdAt      = "2026-04-25T09:30:00",
                updatedAt      = "2026-04-26T16:00:00"
            ),
            DeliveryRow(
                deliveryId     = "demo-004",
                deliveryNumber = "4200758213",
                comments       = "Cold chain — keep refrigerated at 4°C, priority delivery",
                createdBy      = "K.SCHMIDT",
                createdAt      = "2026-04-24T07:00:00",
                updatedAt      = "2026-04-24T07:00:00"
            ),
            DeliveryRow(
                deliveryId     = "demo-005",
                deliveryNumber = "4200819447",
                comments       = "Returns processing — RMA batch April 2026",
                createdBy      = "L.JOHNSON",
                createdAt      = "2026-04-22T13:20:00",
                updatedAt      = "2026-04-23T09:45:00"
            ),
        )
    }
}
